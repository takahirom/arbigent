#!/bin/sh
# Replays an Arbigent run against a device using nothing but adb.
#
# The event log beside this script records what Arbigent actually sent to the device. This script
# sends the same things again, waiting before each step for the element that step targeted so a
# replay that drifts stops and says where, instead of tapping blindly.
exec python3 - "$@" <<'PY'
"""Replay an Arbigent device event log with adb."""

import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

USAGE = """usage: replay.sh <log.jsonl> [options]

  --show            print the steps and exit, without touching a device
  --step N          replay only step N
  --from N          start at step N
  --until N         stop after step N
  --with-init       also replay the setup phase (app launch, state clear)
  --device SERIAL   use this device
  --timeout SEC     how long to wait for each step's target or screen hints
                    (default 10)
  --no-wait         do not wait for targets or screen hints, just send the
                    events
  --backend NAME    how to read the hierarchy: auto (default), android,
                    uiautomator or maestro

exit codes: 0 replayed, 1 usage or unreadable log, 2 the screen diverged
from the recording, 3 a device command failed
"""

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_DIVERGED = 2
EXIT_DEVICE = 3

# No single adb command should take longer than this; a hung adb otherwise hangs the replay.
ADB_TIMEOUT_SECONDS = 60.0

TEXT_KEYS = ["text", "value"]
RESOURCE_ID_KEYS = ["resourceId", "resource-id", "id"]
ACCESSIBILITY_KEYS = [
    "contentDesc",
    "content-desc",
    "accessibilityText",
    "contentDescription",
    "accessibility-id",
    "label",
    "name",
]

BACKENDS = ("auto", "android", "uiautomator", "maestro")

KEY_PRESS_GAP_SECONDS = 0.15
STABILITY_POLL_SECONDS = 0.5
STABILITY_CAP_SECONDS = 3.0


class Options(object):
    def __init__(self):
        self.log = None
        self.show = False
        self.step = None
        self.start = None
        self.until = None
        self.with_init = False
        self.device = None
        self.timeout = 10.0
        self.no_wait = False
        self.backend = "auto"


def parse_args(argv):
    options = Options()
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in ("-h", "--help"):
            sys.stdout.write(USAGE)
            sys.exit(EXIT_OK)
        elif arg == "--show":
            options.show = True
        elif arg == "--with-init":
            options.with_init = True
        elif arg == "--no-wait":
            options.no_wait = True
        elif arg in ("--step", "--from", "--until", "--device", "--timeout", "--backend"):
            index += 1
            if index >= len(argv):
                fail_usage("%s needs a value" % arg)
            value = argv[index]
            if arg == "--step":
                options.step = int_or_fail(arg, value)
            elif arg == "--from":
                options.start = int_or_fail(arg, value)
            elif arg == "--until":
                options.until = int_or_fail(arg, value)
            elif arg == "--device":
                options.device = value
            elif arg == "--backend":
                if value not in BACKENDS:
                    fail_usage("--backend must be one of %s" % ", ".join(BACKENDS))
                options.backend = value
            else:
                options.timeout = timeout_or_fail(arg, value)
        elif arg == "--":
            for rest in argv[index + 1:]:
                if options.log is None:
                    options.log = rest
                else:
                    fail_usage("unexpected argument %s" % rest)
            break
        elif arg.startswith("-") and arg != "-":
            fail_usage("unknown option %s" % arg)
        elif options.log is None:
            options.log = arg
        else:
            fail_usage("unexpected argument %s" % arg)
        index += 1
    if options.log is None:
        fail_usage("no event log given")
    return options


def int_or_fail(name, value):
    try:
        return int(value)
    except ValueError:
        fail_usage("%s needs a whole number, got %r" % (name, value))


def timeout_or_fail(name, value):
    """A finite, positive number of seconds; NaN or infinity would make a wait never end."""
    try:
        seconds = float(value)
    except ValueError:
        fail_usage("%s needs a number, got %r" % (name, value))
    if seconds != seconds or seconds in (float("inf"), float("-inf")) or seconds <= 0:
        fail_usage("%s needs a positive number of seconds, got %r" % (name, value))
    return seconds


def fail_usage(message):
    sys.stderr.write("replay.sh: %s\n\n%s" % (message, USAGE))
    sys.exit(EXIT_USAGE)


class DeviceCommandFailed(Exception):
    """A command the device rejected; carrying on would replay against a screen we did not reach."""


class Diverged(Exception):
    """The screen no longer matches the recording at this step."""


def load_events(path):
    """Every line of the jsonl log, in order.

    A line that does not parse means the log was cut short or edited, and a replay that skipped it
    would send fewer commands than the recording without saying so, so it is refused instead.
    """
    if not os.path.exists(path):
        fail_usage("no such event log: %s" % path)
    events = []
    with open(path, "r") as handle:
        for number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except ValueError:
                fail_usage("%s line %d is not JSON: %s" % (path, number, line[:120]))
            if not isinstance(event, dict) or not isinstance(event.get("type"), str):
                fail_usage("%s line %d is not an event record" % (path, number))
            events.append(event)
    return events


def require_complete(meta, path):
    """A log without a successful end was never finished; replaying part of it is not a replay."""
    if not meta["started"]:
        fail_usage("%s has no scenario_start line" % path)
    if meta["status"] != "success":
        fail_usage(
            "%s does not end with a successful scenario_end (the run was cut short or failed)" % path
        )


def group_steps(events):
    """Turns the flat log into (meta, steps): one entry per step, in the order they ran."""
    meta = {
        "goal": "", "appId": None, "signature": [], "task": "", "width": None, "height": None,
        "started": False, "status": None,
    }
    steps = []
    previous_key = None
    for event in events:
        kind = event.get("type")
        if kind == "scenario_start":
            meta["started"] = True
            meta["goal"] = event.get("goal", "")
            meta["appId"] = event.get("appId")
            meta["task"] = event.get("task", "")
            meta["width"] = event.get("width")
            meta["height"] = event.get("height")
            continue
        if kind == "scenario_end":
            meta["status"] = event.get("status")
            meta["signature"] = event.get("signature", [])
            continue
        number = event.get("step", 0)
        task_index = event.get("taskIndex", 0)
        key = (task_index, number)
        # Groups are consecutive runs of the same key, not one group per key: a task that fell back
        # to the AI launches the app again after the steps it had already replayed, and that second
        # setup block has to stay where it happened rather than merge into the first.
        if key != previous_key:
            previous_key = key
            step = {
                "number": number,
                "taskIndex": task_index,
                "isInit": number == 0,
                "action": "",
                "log": "",
                "memo": None,
                "screenshot": None,
                "target": None,
                "screen": [],
                "events": [],
            }
            steps.append(step)
        else:
            step = steps[-1]
        if kind == "decision":
            step["action"] = event.get("action", "")
            step["log"] = event.get("log", "")
            step["memo"] = event.get("memo")
            step["screenshot"] = event.get("screenshot")
            step["screen"] = [
                hint for hint in event.get("screen") or [] if isinstance(hint, dict)
            ]
        elif kind == "target":
            step["target"] = {
                "text": event.get("text"),
                "resourceId": event.get("resourceId"),
                "accessibilityId": event.get("accessibilityId"),
                "occurrence": event.get("occurrence", 0),
                "bounds": event.get("bounds"),
                "center": event.get("center"),
            }
        elif kind in ("device", "init"):
            step["events"].append(event.get("event", {}))
    return meta, steps


def select_steps(steps, options):
    selected = []
    for step in steps:
        if step["isInit"]:
            if options.with_init and options.step is None:
                selected.append(step)
            continue
        number = step["number"]
        if options.step is not None and number != options.step:
            continue
        if options.start is not None and number < options.start:
            continue
        if options.until is not None and number > options.until:
            continue
        selected.append(step)
    return selected


def describe_target(target):
    if not target:
        return "(none)"
    parts = []
    for key in ("text", "resourceId", "accessibilityId"):
        if target.get(key):
            parts.append("%s='%s'" % (key, target[key]))
    center = target.get("center")
    if isinstance(center, dict):
        parts.append("center=(%s,%s)" % (center.get("x"), center.get("y")))
    return "%s (occurrence %d)" % (", ".join(parts), target.get("occurrence", 0))


def describe_screen(hints):
    parts = []
    for hint in hints:
        for key in ("text", "resourceId", "accessibilityId"):
            if hint.get(key):
                parts.append("%s='%s'" % (key, hint[key]))
                break
    return ", ".join(parts)


def describe_launch_arguments(event):
    arguments = event.get("launchArguments") or {}
    return "".join(
        ", %s=%s" % (key, json.dumps(value, ensure_ascii=False))
        for key, value in sorted(arguments.items())
    )


def describe_event(event):
    kind = event.get("type")
    if kind == "tap":
        return "tap(%s,%s)" % (event.get("x"), event.get("y"))
    if kind == "tap_element":
        return "tap(%s)" % describe_selector(event)
    if kind == "key_press":
        return event.get("keyName", "?")
    if kind == "input_text":
        return 'text("%s")' % event.get("text", "")
    if kind == "swipe":
        return "swipe(%s,%s -> %s,%s, %sms)" % (
            event.get("startX"), event.get("startY"),
            event.get("endX"), event.get("endY"), event.get("durationMs"),
        )
    if kind == "launch_app":
        suffix = ", clearState" if event.get("clearState") else ""
        if event.get("stopApp") is False:
            suffix += ", keepRunning"
        return "launch(%s%s%s)" % (event.get("appId"), suffix, describe_launch_arguments(event))
    if kind == "stop_app":
        return "stop(%s)" % event.get("appId")
    if kind == "clear_state":
        return "clearState(%s)" % event.get("appId")
    if kind == "wait":
        return "wait(%sms)" % event.get("millis")
    if kind == "open_link":
        return "openLink(%s)" % event.get("url")
    if kind == "unsupported":
        return "unsupported(%s)" % event.get("command")
    return str(event)


def describe_selector(event):
    parts = []
    if event.get("textRegex") is not None:
        parts.append("text=%r" % event["textRegex"])
    if event.get("idRegex") is not None:
        parts.append("id=%r" % event["idRegex"])
    if event.get("index"):
        parts.append("index=%s" % event["index"])
    return ", ".join(parts) or "element"


def summarize_events(events):
    if not events:
        return "none"
    parts = []
    for event in events:
        label = describe_event(event)
        if parts and parts[-1][0] == label:
            parts[-1][1] += 1
        else:
            parts.append([label, 1])
    return ", ".join(
        ("%s x%d" % (label, count)) if count > 1 else label for label, count in parts
    )


def show(meta, steps):
    sys.stdout.write("goal: %s\n\n" % meta.get("goal", ""))
    for step in steps:
        if step["isInit"]:
            sys.stdout.write("0. setup\n")
        else:
            sys.stdout.write("%d. %s\n" % (step["number"], step["log"] or step["action"]))
        if step["target"]:
            sys.stdout.write("   target: %s\n" % describe_target(step["target"]))
        elif step["screen"]:
            sys.stdout.write("   screen: %s\n" % describe_screen(step["screen"]))
        sys.stdout.write("   device: %s\n" % summarize_events(step["events"]))
    if meta.get("signature"):
        sys.stdout.write("\nexpect: resource ids %s\n" % ", ".join(meta["signature"]))


def adb_command(args):
    """The argv for one adb call.

    Everything after `shell` or `exec-out` is run by the device's shell as one line, so each remote
    argument is quoted for it: a typed text with a space or a launch extra with a `;` in it must
    reach the device as data, not as more commands.
    """
    if args and args[0] in ("shell", "exec-out"):
        return ["adb", args[0], " ".join(shlex.quote(arg) for arg in args[1:])]
    return ["adb"] + args


def adb(options, args, capture=False):
    """Runs one adb command and returns (code, stdout, stderr).

    --device is passed as ANDROID_SERIAL so `android` sees it too. Output is captured either way so
    a failure can be quoted; with capture=False it is also echoed, as it was before.
    """
    env = dict(os.environ)
    if options.device:
        env["ANDROID_SERIAL"] = options.device
    try:
        process = subprocess.run(
            adb_command(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
            timeout=ADB_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return 124, b"", ("adb %s took longer than %.0fs" % (args[0], ADB_TIMEOUT_SECONDS)).encode()
    if not capture:
        if process.stdout:
            sys.stdout.write(process.stdout.decode("utf-8", "replace"))
        if process.stderr:
            sys.stderr.write(process.stderr.decode("utf-8", "replace"))
    return process.returncode, process.stdout, process.stderr


def checked(result, what):
    """Raises DeviceCommandFailed unless the adb result succeeded."""
    code, out, err = result
    if code != 0:
        detail = (out + err).decode("utf-8", "replace").strip()
        raise DeviceCommandFailed("%s failed (exit %d)%s" % (what, code, ": " + detail if detail else ""))
    return result


def require_adb():
    if shutil.which("adb") is None:
        sys.stderr.write("replay.sh: adb is not on PATH\n")
        sys.exit(EXIT_USAGE)


def first_non_blank(node, keys):
    for key in keys:
        value = node.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return None


def center_of(value):
    """A point from either geometry shape a dump uses, or None when it is neither.

    `uiautomator dump` reports a rectangle as "[x1,y1][x2,y2]" and the middle of it is the point to
    tap. `android layout` reports no rectangle at all, only a "[x,y]" centre, so two numbers are
    already the answer.
    """
    if isinstance(value, dict):
        return value
    if not isinstance(value, str):
        return None
    numbers = [int(number) for number in re.findall(r"-?\d+", value)]
    if len(numbers) >= 4:
        left, top, right, bottom = numbers[:4]
        return {"x": (left + right) // 2, "y": (top + bottom) // 2}
    if len(numbers) == 2:
        return {"x": numbers[0], "y": numbers[1]}
    return None


def normalize(node):
    bounds = node.get("bounds")
    center = center_of(node.get("center"))
    if center is None:
        center = center_of(bounds)
    return {
        "text": first_non_blank(node, TEXT_KEYS),
        "resourceId": first_non_blank(node, RESOURCE_ID_KEYS),
        "accessibilityId": first_non_blank(node, ACCESSIBILITY_KEYS),
        "bounds": bounds,
        "center": center,
    }


def dump_tree(options, backend=None):
    """The nodes currently on screen, as a flat list.

    Every hierarchy read goes through here, so a replay reads the screen exactly one way and the
    choice is a single flag. `auto` tries `uiautomator dump` first, then `android layout`.
    uiautomator comes first because it reports a far fuller tree: measured on a TV app, a video
    player screen gave 3 nodes through `android layout` against 23, nineteen of them with resource
    ids, through uiautomator, and a home screen gave 35 against 135. That tree is also the closer
    match to what Maestro recorded, and uiautomator needs nothing but adb.
    """
    backend = backend or options.backend
    if backend == "android":
        return dump_tree_android_cli(options) or []
    if backend == "uiautomator":
        return dump_tree_uiautomator(options)
    if backend == "maestro":
        return dump_tree_maestro(options)
    nodes = dump_tree_uiautomator(options)
    if nodes:
        return nodes
    if shutil.which("android") is not None:
        return dump_tree_android_cli(options) or []
    return nodes


def dump_tree_maestro(options):
    """Not implemented: `maestro hierarchy` output was never verified against a device here."""
    raise NotImplementedError(
        "the maestro backend is not implemented; use --backend android or --backend uiautomator"
    )


def dump_tree_android_cli(options):
    env = dict(os.environ)
    if options.device:
        env["ANDROID_SERIAL"] = options.device
    try:
        process = subprocess.run(
            ["android", "layout"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
            timeout=ADB_TIMEOUT_SECONDS,
        )
        if process.returncode != 0:
            return None
        payload = json.loads(process.stdout.decode("utf-8", "replace"))
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return None
    # The CLI prints a flat list of nodes, but be forgiving about a wrapper object.
    if isinstance(payload, dict):
        for key in ("nodes", "elements", "layout"):
            if isinstance(payload.get(key), list):
                payload = payload[key]
                break
    if not isinstance(payload, list):
        return None
    return [normalize(node) for node in payload if isinstance(node, dict)]


UIAUTOMATOR_DUMP_ATTEMPTS = 3


def dump_tree_uiautomator(options):
    """A flat list from `uiautomator dump`, retried because the dump refuses to run mid-animation.

    While the screen is still moving the command exits 0 but prints "could not get idle state"
    instead of writing the file, so the exit code alone is not enough to tell success from failure.
    """
    # One file per runner process, removed once read: the dump holds every string on screen, and
    # two replays against the same device must not read each other's.
    remote = "/sdcard/arbigent-replay-dump-%d.xml" % os.getpid()
    try:
        for attempt in range(UIAUTOMATOR_DUMP_ATTEMPTS):
            if attempt:
                time.sleep(STABILITY_POLL_SECONDS)
            code, out, err = adb(options, ["shell", "uiautomator", "dump", remote], capture=True)
            combined = (out + err).decode("utf-8", "replace")
            if code != 0 or "ERROR" in combined or "could not get idle state" in combined:
                continue
            code, out, _ = adb(options, ["exec-out", "cat", remote], capture=True)
            if code != 0 or not out.strip():
                continue
            try:
                root = ElementTree.fromstring(out.decode("utf-8", "replace"))
            except ElementTree.ParseError:
                continue
            nodes = []
            flatten_xml(root, nodes)
            return nodes
        return []
    finally:
        adb(options, ["shell", "rm", "-f", remote], capture=True)


def flatten_xml(element, out):
    """Depth first, so siblings keep the order the device reported, which occurrence counts on."""
    attributes = element.attrib
    if attributes:
        out.append(
            normalize(
                {
                    "text": attributes.get("text"),
                    "resourceId": attributes.get("resource-id"),
                    "contentDesc": attributes.get("content-desc"),
                    "bounds": attributes.get("bounds"),
                }
            )
        )
    for child in list(element):
        flatten_xml(child, out)


def short_resource_id(value):
    """The part of a resource id after the last "/", which is the only part both sides always have.

    `android layout` prints a resource id without its package, as `fragment_container`, while
    uiautomator and Maestro print `com.example.app:id/fragment_container`. Neither form is wrong, so
    ids are compared on the segment they share.
    """
    if not isinstance(value, str):
        return value
    return value.rsplit("/", 1)[-1]


def resource_ids_match(recorded, on_screen):
    """Equal ids match; a qualified id also matches the bare one the `android` CLI prints."""
    if recorded == on_screen:
        return True
    if not isinstance(recorded, str) or not isinstance(on_screen, str):
        return False
    if ":id/" in recorded and ":id/" in on_screen:
        return False
    return short_resource_id(recorded) == short_resource_id(on_screen)


def attribute_matches(node, target, key):
    if key == "resourceId":
        return resource_ids_match(target.get(key), node.get(key))
    return node.get(key) == target[key]


def identity_keys(target):
    return [key for key in ("resourceId", "text", "accessibilityId") if target.get(key)]


def find_match(nodes, target):
    """Mirrors Arbigent's own element matching, then relaxes it one attribute at a time.

    A strict match on every recorded attribute wins. Failing that, resourceId, then text, then
    contentDesc is tried on its own, because Arbigent reads an element's text from its descendants
    while a flattened dump splits the container and its label into separate nodes, so a node that is
    plainly the right one can carry only part of the recorded identity.
    """
    if not target:
        return None
    keys = identity_keys(target)
    if not keys:
        return None
    candidate_sets = [keys]
    if len(keys) > 1:
        candidate_sets += [[key] for key in keys]
    for subset in candidate_sets:
        matched = [
            node for node in nodes if all(attribute_matches(node, target, key) for key in subset)
        ]
        if not matched:
            continue
        occurrence = target.get("occurrence", 0)
        if occurrence < len(matched):
            return matched[occurrence]
        # An element that moved up the list is still the right element; the recorded index only
        # picks between equally matching candidates.
        return matched[0]
    return None


def identity_is_resolvable(nodes, target):
    """Whether the dump could show this identity at all.

    Maestro reads the hierarchy through its own instrumentation and sees attributes, notably Compose
    test tags surfaced as resource ids, that `android layout` and `uiautomator dump` do not. If no
    node in the dump carries a value of any attribute kind the identity uses, the identity is simply
    invisible from here and its absence says nothing about the screen.

    The heuristic is deliberately coarse: a screen where only a system node happens to carry a
    resource id counts as resolvable, so a Compose target can still be reported as diverged.
    """
    for key in identity_keys(target):
        for node in nodes:
            value = node.get(key)
            if isinstance(value, str) and value.strip():
                return True
    return False


def wait_for(options, target):
    """Waits for the step's target, then for the screen to stop changing.

    Returns (status, nodes) where status is one of:

      matched     the target was found, and the screen then settled.
      unresolved  the settled screen shows no value of any attribute kind the identity uses, so the
                  identity is invisible from this backend and its absence says nothing. The step
                  goes ahead.
      absent      the screen does show those attribute kinds and the target was still not there when
                  the timeout ran out.

    A dump taken mid-transition can look unresolvable while the screen it is turning into is not, so
    an unresolvable dump is only believed once the screen has settled.
    """
    deadline = time.monotonic() + options.timeout
    nodes = []
    while True:
        nodes = dump_tree(options)
        if find_match(nodes, target) is not None:
            return "matched", wait_until_stable(options, nodes)
        if nodes and not identity_is_resolvable(nodes, target):
            stable = wait_until_stable(options, nodes, cap=max(0.0, deadline - time.monotonic()))
            if find_match(stable, target) is not None:
                return "matched", stable
            if not identity_is_resolvable(stable, target):
                sys.stdout.write("   wait: unresolved target, used stability\n")
                return "unresolved", stable
            # The first dump was mid-transition; the settled screen does show identities, so the
            # target's absence is meaningful again and the deadline still applies.
            nodes = stable
        if time.monotonic() >= deadline:
            return "absent", nodes
        time.sleep(STABILITY_POLL_SECONDS)


def wait_until_stable(options, nodes, cap=STABILITY_CAP_SECONDS):
    """Two consecutive identical dumps, capped, so an animation does not eat the next tap."""
    deadline = time.monotonic() + cap
    previous = nodes
    while time.monotonic() < deadline:
        time.sleep(STABILITY_POLL_SECONDS)
        current = dump_tree(options)
        if current == previous:
            return current
        previous = current
    return previous


REGEX_FLAGS = re.IGNORECASE | re.DOTALL | re.MULTILINE


def selector_matches(node, event):
    """Whether a node is what Maestro's text/id regex selector would have picked.

    Maestro matches the whole value, case-insensitively, with `.` spanning lines; text also matches
    the accessibility label. An id regex is tried against the short id too, for the `android` CLI.
    """
    text_regex = event.get("textRegex")
    id_regex = event.get("idRegex")
    if text_regex is not None:
        values = [node.get("text"), node.get("accessibilityId")]
        if not any(
            isinstance(value, str) and re.fullmatch(text_regex, value, REGEX_FLAGS) for value in values
        ):
            return False
    if id_regex is not None:
        node_id = node.get("resourceId")
        if not isinstance(node_id, str):
            return False
        if not re.fullmatch(id_regex, node_id, REGEX_FLAGS) and not re.fullmatch(
            short_resource_id(id_regex), short_resource_id(node_id), REGEX_FLAGS
        ):
            return False
    return True


def resolve_element(nodes, event):
    """The node an element tap should land on now, or None."""
    try:
        matched = [node for node in nodes if selector_matches(node, event)]
    except re.error:
        return None
    index = event.get("index") or 0
    if index < len(matched):
        return matched[index]
    return None


def tap_element(options, event, step, nodes):
    """Taps the element the recorded run tapped by selector, wherever it is on screen now.

    The current hierarchy decides; the recorded target center is only a last resort for a backend
    that cannot see the element, and a selector nothing matches is a divergence, since Maestro would
    have refused the tap at the same point.
    """
    node = resolve_element(nodes, event) or resolve_element(dump_tree(options), event)
    center = node.get("center") if node else None
    if center is None and step.get("target") and step["target"].get("center"):
        center = step["target"]["center"]
        sys.stdout.write("   tap: element not in the dump, using the recorded position\n")
    if not center:
        raise Diverged("no element on screen matches %s" % describe_selector(event))
    checked(
        adb(options, ["shell", "input", "tap", str(center["x"]), str(center["y"])]),
        "tap on %s" % describe_selector(event),
    )


def send_event(options, event, step, nodes):
    """Sends one recorded event. Returns False for an event adb cannot reproduce."""
    kind = event.get("type")
    if kind == "tap":
        checked(adb(options, ["shell", "input", "tap", str(event["x"]), str(event["y"])]), "tap")
    elif kind == "tap_element":
        tap_element(options, event, step, nodes)
    elif kind == "key_press":
        checked(adb(options, ["shell", "input", "keyevent", str(event["keyName"])]), "key press")
    elif kind == "input_text":
        text = event.get("text", "")
        if not typeable(text):
            raise Diverged(
                "adb shell input text cannot type %r (only printable ASCII travels through it)" % text
            )
        checked(adb(options, ["shell", "input", "text", escape_text(text)]), "text input")
    elif kind == "swipe":
        checked(adb(options, [
            "shell", "input", "swipe",
            str(event["startX"]), str(event["startY"]),
            str(event["endX"]), str(event["endY"]),
            str(event.get("durationMs", 400)),
        ]), "swipe")
    elif kind == "launch_app":
        app_id = str(event["appId"])
        if event.get("clearState"):
            clear_state(options, app_id)
        elif event.get("stopApp", True):
            # Maestro force-stops before launching unless told not to; pm clear already did.
            checked(adb(options, ["shell", "am", "force-stop", app_id], capture=True), "force-stop")
        launch_app(options, app_id, event.get("launchArguments") or {})
    elif kind == "stop_app":
        checked(adb(options, ["shell", "am", "force-stop", str(event["appId"])]), "force-stop")
    elif kind == "clear_state":
        clear_state(options, str(event["appId"]))
    elif kind == "wait":
        time.sleep(float(event.get("millis", 0)) / 1000.0)
    elif kind == "open_link":
        checked(adb(options, [
            "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
            "-d", str(event["url"]),
        ], capture=True), "open link")
    else:
        return False
    return True


def clear_state(options, app_id):
    # `pm clear` reports a package it could not clear with "Failed" on stdout and exit code 0.
    code, out, err = adb(options, ["shell", "pm", "clear", app_id], capture=True)
    combined = (out + err).decode("utf-8", "replace")
    if code != 0 or "Success" not in combined:
        raise DeviceCommandFailed("pm clear %s failed: %s" % (app_id, combined.strip() or "no output"))


def launcher_activity(options, app_id):
    """The component the launcher would start, or None when the package manager cannot say."""
    code, out, _ = adb(options, [
        "shell", "cmd", "package", "resolve-activity", "--brief",
        "-c", "android.intent.category.LAUNCHER", app_id,
    ], capture=True)
    if code != 0:
        return None
    for line in reversed(out.decode("utf-8", "replace").splitlines()):
        line = line.strip()
        if "/" in line and not line.startswith("priority="):
            return line
    return None


def extra_flag(value):
    """The `am start` flag that carries a JSON value of this type as an intent extra.

    The log keeps extras as JSON primitives, so a whole number is passed as an int extra; Maestro's
    YAML gives it the same type, and an app reading it as a long would already differ under Maestro.
    """
    if isinstance(value, bool):
        return "--ez", "true" if value else "false"
    if isinstance(value, int):
        return "--ei", str(value)
    if isinstance(value, float):
        return "--ef", str(value)
    return "--es", str(value)


def launch_app(options, app_id, arguments):
    """Starts the app the way the recorded run did.

    Launch extras only travel through `am start`, and `am start` needs a component, so the launcher
    activity is resolved first. The monkey fallback handles a package the resolver cannot describe,
    at the cost of any extras, and says so.
    """
    component = launcher_activity(options, app_id)
    if component is None:
        if arguments:
            sys.stdout.write("   launch: could not resolve the launcher activity, extras dropped\n")
        code, out, err = adb(options, [
            "shell", "monkey", "-p", app_id, "-c", "android.intent.category.LAUNCHER", "1",
        ], capture=True)
        combined = (out + err).decode("utf-8", "replace")
        if code != 0 or "Events injected: 1" not in combined:
            raise DeviceCommandFailed("could not launch %s: %s" % (app_id, combined.strip()))
        return
    command = ["shell", "am", "start", "-W", "-n", component]
    for key in sorted(arguments):
        flag, rendered = extra_flag(arguments[key])
        command += [flag, key, rendered]
    code, out, err = adb(options, command, capture=True)
    combined = (out + err).decode("utf-8", "replace")
    if code != 0 or "Error" in combined:
        raise DeviceCommandFailed("am start %s failed: %s" % (component, combined.strip()))


def typeable(text):
    """`input text` only carries printable ASCII faithfully; anything else comes out changed."""
    return all(" " <= character <= "~" for character in text)


def escape_text(text):
    """`input text` splits its argument on spaces, so a space travels as `%s`.

    The argument is shell-quoted on the way in, so no other character needs escaping. A literal
    `%s` in the text is the one thing this cannot express: the device turns it into a space too.
    """
    return text.replace(" ", "%s")


def send_step_events(options, step, nodes):
    previous_was_key = False
    for event in step["events"]:
        if event.get("type") == "key_press" and previous_was_key:
            time.sleep(KEY_PRESS_GAP_SECONDS)
        if not send_event(options, event, step, nodes):
            return event
        previous_was_key = event.get("type") == "key_press"
    return None


def report_divergence(options, meta, step, remaining, nodes, reason):
    out = sys.stderr
    out.write("\nDIVERGED\n")
    out.write("  goal: %s\n" % meta.get("goal", ""))
    out.write("  step %d: %s\n" % (step["number"], step["log"] or step["action"]))
    if step.get("memo"):
        out.write("  memo: %s\n" % step["memo"].replace("\n", " "))
    out.write("  reason: %s\n" % reason)
    out.write("  expected: %s\n" % describe_target(step["target"]))
    out.write("  on screen now:\n")
    shown = 0
    for node in nodes:
        label = describe_node(node)
        if not label:
            continue
        out.write("    - %s\n" % label)
        shown += 1
        if shown >= 40:
            out.write("    ... (more nodes not shown)\n")
            break
    if not shown:
        out.write("    (nothing readable)\n")
    if remaining:
        out.write("  remaining steps:\n")
        for later in remaining:
            out.write("    %d. %s\n" % (later["number"], later["log"] or later["action"]))
    out.write("  resume with: ./replay.sh %s --from %d\n" % (shlex.quote(options.log), step["number"]))


def describe_node(node):
    parts = []
    for key in ("text", "resourceId", "accessibilityId"):
        if node.get(key):
            parts.append("%s='%s'" % (key, node[key]))
    return ", ".join(parts)


def wait_for_screen(options, hints):
    """Waits until one of the screen hints a targetless step recorded is on screen, then settles.

    Advisory only: the hints say which screen the decision was looking at, not what it acted on, so
    running out of time is reported and the step still goes ahead. Returns True when a hint matched.
    """
    deadline = time.monotonic() + options.timeout
    while True:
        nodes = dump_tree(options)
        if any(find_match(nodes, hint) is not None for hint in hints):
            wait_until_stable(options, nodes)
            return True
        if time.monotonic() >= deadline:
            sys.stdout.write(
                "   wait: none of the recorded screen hints appeared within %.0fs, continuing\n"
                % options.timeout
            )
            wait_until_stable(options, nodes)
            return False
        time.sleep(STABILITY_POLL_SECONDS)


def check_signature(options, signature):
    if not signature:
        return
    # The last step may still be animating into the end screen.
    nodes = wait_until_stable(options, dump_tree(options))
    present = set()
    for node in nodes:
        for recorded in signature:
            if resource_ids_match(recorded, node.get("resourceId")):
                present.add(recorded)
    if present:
        sys.stdout.write(
            "PASS: end screen matches %d of %d recorded ids\n" % (len(present), len(signature))
        )
    else:
        sys.stdout.write(
            "WARN: none of the recorded end-screen ids are present (%s)\n" % ", ".join(signature)
        )


def main():
    options = parse_args(sys.argv[1:])
    meta, steps = group_steps(load_events(options.log))
    require_complete(meta, options.log)
    selected = select_steps(steps, options)
    if options.show:
        show(meta, selected)
        return EXIT_OK
    if not selected:
        sys.stderr.write("replay.sh: nothing to replay for the given range\n")
        return EXIT_USAGE
    require_adb()
    if options.backend == "android" and shutil.which("android") is None:
        fail_usage("--backend android needs the `android` CLI on PATH")
    for position, step in enumerate(selected):
        remaining = selected[position + 1:]
        label = "setup" if step["isInit"] else (step["log"] or step["action"])
        sys.stdout.write("step %d: %s\n" % (step["number"], label))
        nodes = []
        if step["target"] and not options.no_wait and not step["isInit"]:
            status, nodes = wait_for(options, step["target"])
            if status == "absent":
                report_divergence(
                    options, meta, step, remaining, nodes,
                    "the target never appeared within %.0fs" % options.timeout,
                )
                return EXIT_DIVERGED
        elif step["screen"] and not options.no_wait and not step["isInit"]:
            wait_for_screen(options, step["screen"])
        try:
            unsupported = send_step_events(options, step, nodes)
        except Diverged as error:
            report_divergence(options, meta, step, remaining, nodes or dump_tree(options), str(error))
            return EXIT_DIVERGED
        except DeviceCommandFailed as error:
            sys.stderr.write("\nreplay.sh: step %d: %s\n" % (step["number"], error))
            sys.stderr.write(
                "  resume with: ./replay.sh %s --from %d\n" % (shlex.quote(options.log), step["number"])
            )
            return EXIT_DEVICE
        if unsupported is not None:
            report_divergence(
                options, meta, step, remaining, nodes or dump_tree(options),
                "this step used %s, which adb cannot reproduce" % describe_event(unsupported),
            )
            return EXIT_DIVERGED
    check_signature(options, meta.get("signature", []))
    return EXIT_OK


def run():
    try:
        return main()
    except NotImplementedError as error:
        sys.stderr.write("replay.sh: %s\n" % error)
        return EXIT_USAGE
    except DeviceCommandFailed as error:
        sys.stderr.write("replay.sh: %s\n" % error)
        return EXIT_DEVICE


if __name__ == "__main__":
    sys.exit(run())
PY
