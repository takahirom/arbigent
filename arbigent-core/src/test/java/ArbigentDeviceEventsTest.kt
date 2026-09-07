package io.github.takahirom.arbigent.sample.test

import io.github.takahirom.arbigent.ArbigentDeviceEvent
import io.github.takahirom.arbigent.ArbigentDeviceOs
import io.github.takahirom.arbigent.KeyPressAgentAction
import io.github.takahirom.arbigent.toArbigentDeviceEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import maestro.KeyCode
import maestro.Point
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand

private const val WIDTH = 1080
private const val HEIGHT = 1920
private const val TS = 42L

private fun MaestroCommand.events(os: ArbigentDeviceOs = ArbigentDeviceOs.Android): List<ArbigentDeviceEvent> =
  toArbigentDeviceEvents(WIDTH, HEIGHT, os, TS)

class ArbigentDeviceEventsTest {
  @Test
  fun `tap on point becomes a tap`() {
    val events = MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "120,340")).events()
    assertEquals(listOf(ArbigentDeviceEvent.Tap(120, 340, timestamp = TS)), events)
  }

  @Test
  fun `tap on an element keeps its selector so a replay can find it again`() {
    assertEquals(
      listOf(ArbigentDeviceEvent.TapElement(textRegex = "Settings", index = 1, timestamp = TS)),
      MaestroCommand(
        tapOnElement = TapOnElementCommand(selector = ElementSelector(textRegex = "Settings", index = "1"))
      ).events(),
    )
    assertEquals(
      listOf(ArbigentDeviceEvent.TapElement(idRegex = ".*button", timestamp = TS)),
      MaestroCommand(
        tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = ".*button"))
      ).events(),
    )
    // An explicit index of 0 is not the same selection as no index at all, so it has to survive.
    assertEquals(
      listOf(ArbigentDeviceEvent.TapElement(textRegex = "Play", index = 0, timestamp = TS)),
      MaestroCommand(
        tapOnElement = TapOnElementCommand(selector = ElementSelector(textRegex = "Play", index = "0"))
      ).events(),
    )
    assertTrue(
      MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(enabled = true)))
        .events().single() is ArbigentDeviceEvent.Unsupported,
      "a selector with nothing to search the hierarchy for cannot be replayed",
    )
  }

  @Test
  fun `a tap keeps maestro's own retry so the replay gets the same attempts`() {
    // Arbigent's taps leave retryIfNoChange unset, but a Maestro flow used as an initializer runs
    // through this recorder, and dropping the flag would give the replayed tap one attempt where
    // the recorded one had two.
    assertEquals(
      listOf(ArbigentDeviceEvent.Tap(10, 20, retryIfNoChange = true, timestamp = TS)),
      MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "10,20", retryIfNoChange = true)).events(),
    )
    assertEquals(
      listOf(ArbigentDeviceEvent.TapElement(textRegex = "Settings", retryIfNoChange = true, timestamp = TS)),
      MaestroCommand(
        tapOnElement = TapOnElementCommand(
          selector = ElementSelector(textRegex = "Settings"),
          retryIfNoChange = true,
        )
      ).events(),
    )
    // Unset stays unset: Maestro reads null as false, and writing false into the log would freeze
    // today's default instead of recording that the command asked for nothing.
    assertEquals(
      null,
      (MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "10,20")).events().single()
        as ArbigentDeviceEvent.Tap).retryIfNoChange,
    )
  }

  @Test
  fun `a percentage tap point is unsupported because the driver resolves it, not us`() {
    val events = MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "50%,50%")).events()
    assertTrue(events.single() is ArbigentDeviceEvent.Unsupported)
  }

  @Test
  fun `maestro keys are recorded under their maestro name`() {
    fun keyName(code: KeyCode): String {
      val event = MaestroCommand(pressKeyCommand = PressKeyCommand(code)).events().single()
      return (event as ArbigentDeviceEvent.KeyPress).keyName
    }
    assertEquals("REMOTE_UP", keyName(KeyCode.REMOTE_UP))
    assertEquals("REMOTE_CENTER", keyName(KeyCode.REMOTE_CENTER))
    assertEquals("BACKSPACE", keyName(KeyCode.BACKSPACE))
    assertEquals("LOCK", keyName(KeyCode.LOCK))
    assertEquals("REMOTE_PLAY_PAUSE", keyName(KeyCode.REMOTE_PLAY_PAUSE))
    assertEquals("REMOTE_MENU", keyName(KeyCode.REMOTE_MENU))
    assertEquals("ENTER", keyName(KeyCode.ENTER))
  }

  @Test
  fun `every recorded key name resolves back to its key`() {
    KeyCode.entries.forEach { code ->
      val event = MaestroCommand(pressKeyCommand = PressKeyCommand(code)).events().single()
      val recorded = (event as ArbigentDeviceEvent.KeyPress).keyName
      assertEquals(code, KeyPressAgentAction.resolveKeyCode(recorded), "key $code")
    }
  }

  @Test
  fun `back press becomes the back key`() {
    val events = MaestroCommand(backPressCommand = BackPressCommand()).events()
    assertEquals(listOf(ArbigentDeviceEvent.KeyPress("BACK", TS)), events)
  }

  @Test
  fun `back on web cannot be replayed as a key press`() {
    val events = MaestroCommand(backPressCommand = BackPressCommand()).events(ArbigentDeviceOs.Web)
    assertEquals(
      listOf(ArbigentDeviceEvent.Unsupported("back on web", TS)),
      events,
      "the web driver navigates history instead and rejects the BACK key, so a key press would " +
        "fail the replay at this step",
    )
  }

  @Test
  fun `input text is carried through`() {
    val events = MaestroCommand(inputTextCommand = InputTextCommand("hello world")).events()
    assertEquals(listOf(ArbigentDeviceEvent.InputText("hello world", TS)), events)
  }

  @Test
  fun `erase text becomes one delete per character`() {
    val events = MaestroCommand(eraseTextCommand = EraseTextCommand(charactersToErase = 3)).events()
    assertEquals(List(3) { ArbigentDeviceEvent.KeyPress("BACKSPACE", TS) }, events)
  }

  @Test
  fun `app lifecycle commands map to their app ids`() {
    assertEquals(
      listOf(ArbigentDeviceEvent.LaunchApp("com.example.app", clearState = true, timestamp = TS)),
      MaestroCommand(
        launchAppCommand = LaunchAppCommand(appId = "com.example.app", clearState = true)
      ).events(),
      "maestro force-stops before launching unless told otherwise, so stopApp defaults to true",
    )
    assertEquals(
      listOf(ArbigentDeviceEvent.LaunchApp("com.example.app", stopApp = false, timestamp = TS)),
      MaestroCommand(
        launchAppCommand = LaunchAppCommand(appId = "com.example.app", stopApp = false)
      ).events(),
    )
    assertEquals(
      listOf(ArbigentDeviceEvent.StopApp("com.example.app", TS)),
      MaestroCommand(stopAppCommand = StopAppCommand(appId = "com.example.app")).events(),
    )
    assertEquals(
      listOf(
        ArbigentDeviceEvent.LaunchApp(
          "com.example.app",
          launchArguments = mapOf(
            "debug_menu" to JsonPrimitive(true),
            "retries" to JsonPrimitive(3),
            "entry" to JsonPrimitive("settings"),
          ),
          timestamp = TS,
        )
      ),
      MaestroCommand(
        launchAppCommand = LaunchAppCommand(
          appId = "com.example.app",
          launchArguments = mapOf("debug_menu" to true, "retries" to 3, "entry" to "settings"),
        )
      ).events(),
      "launch extras decide what some apps show first, so they have to survive into the log",
    )
    assertEquals(
      listOf(ArbigentDeviceEvent.ClearState("com.example.app", TS)),
      MaestroCommand(clearStateCommand = ClearStateCommand(appId = "com.example.app")).events(),
    )
  }

  @Test
  fun `a launch keeps the permissions and keychain reset it asked for`() {
    assertEquals(
      listOf(
        ArbigentDeviceEvent.LaunchApp(
          "com.example.app",
          permissions = mapOf("all" to "deny"),
          clearKeychain = true,
          timestamp = TS,
        )
      ),
      MaestroCommand(
        launchAppCommand = LaunchAppCommand(
          appId = "com.example.app",
          permissions = mapOf("all" to "deny"),
          clearKeychain = true,
        )
      ).events(),
      "a launch that denies a permission or resets the keychain starts the app in a state a " +
        "replay cannot reach by launching with maestro's defaults",
    )
    val defaults = MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app"))
      .events()
      .single() as ArbigentDeviceEvent.LaunchApp
    assertEquals(null, defaults.permissions, "maestro fills in its own default for an omitted value")
    assertEquals(null, defaults.clearKeychain)
  }

  @Test
  fun `open link carries the url`() {
    val events = MaestroCommand(openLinkCommand = OpenLinkCommand(link = "https://example.com")).events()
    assertEquals(listOf(ArbigentDeviceEvent.OpenLink("https://example.com", TS)), events)
  }

  @Test
  fun `a scroll is the same upward swipe maestro performs on both platforms`() {
    val expected = listOf(ArbigentDeviceEvent.Swipe(540, 960, 540, 192, 400L, TS))
    assertEquals(expected, MaestroCommand(scrollCommand = ScrollCommand()).events())
    assertEquals(expected, MaestroCommand(scrollCommand = ScrollCommand()).events(ArbigentDeviceOs.Ios))
  }

  @Test
  fun `a scroll on web is not the swipe the other platforms perform`() {
    assertEquals(
      listOf(ArbigentDeviceEvent.Unsupported("scroll on web", TS)),
      MaestroCommand(scrollCommand = ScrollCommand()).events(ArbigentDeviceOs.Web),
      "the web driver scrolls the document with JavaScript, so a page that swallows touch " +
        "gestures would not move at all for a replayed swipe",
    )
  }

  @Test
  fun `each swipe direction keeps maestro's own start and end points`() {
    fun swipe(
      direction: SwipeDirection,
      os: ArbigentDeviceOs = ArbigentDeviceOs.Android,
    ): ArbigentDeviceEvent.Swipe {
      val command = MaestroCommand(swipeCommand = SwipeCommand(direction = direction, duration = 500L))
      return command.events(os).single() as ArbigentDeviceEvent.Swipe
    }
    assertEquals(ArbigentDeviceEvent.Swipe(540, 960, 540, 192, 500L, TS), swipe(SwipeDirection.UP))
    assertEquals(ArbigentDeviceEvent.Swipe(540, 384, 540, 1728, 500L, TS), swipe(SwipeDirection.DOWN))
    assertEquals(ArbigentDeviceEvent.Swipe(108, 960, 972, 960, 500L, TS), swipe(SwipeDirection.RIGHT))
    assertEquals(ArbigentDeviceEvent.Swipe(972, 960, 108, 960, 500L, TS), swipe(SwipeDirection.LEFT))
    // Only the upward swipe differs between the drivers: iOS starts it near the bottom edge.
    assertEquals(
      ArbigentDeviceEvent.Swipe(540, 1728, 540, 192, 500L, TS),
      swipe(SwipeDirection.UP, ArbigentDeviceOs.Ios),
    )
    assertEquals(
      ArbigentDeviceEvent.Swipe(540, 384, 540, 1728, 500L, TS),
      swipe(SwipeDirection.DOWN, ArbigentDeviceOs.Ios),
    )
  }

  @Test
  fun `an explicit swipe keeps its own points`() {
    val command = MaestroCommand(
      swipeCommand = SwipeCommand(startPoint = Point(10, 20), endPoint = Point(30, 40), duration = 250L)
    )
    assertEquals(
      listOf(ArbigentDeviceEvent.Swipe(10, 20, 30, 40, 250L, TS)),
      command.events(),
    )
  }

  @Test
  fun `waiting for an animation becomes a wait`() {
    val command = MaestroCommand(waitForAnimationToEndCommand = WaitForAnimationToEndCommand(timeout = "100"))
    assertEquals(listOf(ArbigentDeviceEvent.Wait(100L, TS)), command.events())
  }

  @Test
  fun `a screenshot is arbigent's own observation and is not replayed`() {
    val command = MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "shot"))
    assertEquals(emptyList(), command.events())
  }

  @Test
  fun `taps that are not a plain single tap are unsupported instead of downgraded`() {
    val plain = TapOnElementCommand(selector = ElementSelector(textRegex = "Settings"))
    assertTrue(plain.let { MaestroCommand(tapOnElement = it) }.events().single() is ArbigentDeviceEvent.TapElement)
    listOf(
      plain.copy(longPress = true),
      plain.copy(repeat = TapRepeat(2, 100L)),
      plain.copy(relativePoint = "50%,50%"),
      plain.copy(selector = ElementSelector(textRegex = "Settings", below = ElementSelector(textRegex = "Account"))),
      plain.copy(selector = ElementSelector(textRegex = "Settings", enabled = true)),
      plain.copy(selector = ElementSelector(idRegex = "row", childOf = ElementSelector(idRegex = "list"))),
    ).forEach { command ->
      assertTrue(
        MaestroCommand(tapOnElement = command).events().single() is ArbigentDeviceEvent.Unsupported,
        "a replay can only tap the first text/id match once, so $command must fall back to the AI",
      )
    }
    assertTrue(
      MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "10,20", longPress = true))
        .events().single() is ArbigentDeviceEvent.Unsupported
    )
    assertTrue(
      MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "10,20", repeat = TapRepeat(3, 50L)))
        .events().single() is ArbigentDeviceEvent.Unsupported
    )
  }

  @Test
  fun `a swipe anchored on an element is unsupported`() {
    val command = MaestroCommand(
      swipeCommand = SwipeCommand(
        direction = SwipeDirection.UP,
        elementSelector = ElementSelector(idRegex = "list"),
        duration = 400L,
      )
    )
    assertTrue(command.events().single() is ArbigentDeviceEvent.Unsupported)
  }

  @Test
  fun `kill app is not the same as force-stop and is left to the fallback`() {
    val events = MaestroCommand(killAppCommand = KillAppCommand(appId = "com.example.app")).events()
    assertTrue(events.single() is ArbigentDeviceEvent.Unsupported)
  }
}
