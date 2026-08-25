import io.github.takahirom.arbigent.KeyPressAgentAction
import maestro.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyPressAgentActionTest {
  @Test
  fun `resolves maestro description names`() {
    assertEquals(KeyCode.ENTER, KeyPressAgentAction.resolveKeyCode("Enter"))
    assertEquals(KeyCode.BACKSPACE, KeyPressAgentAction.resolveKeyCode("backspace"))
    assertEquals(KeyCode.REMOTE_UP, KeyPressAgentAction.resolveKeyCode("Remote Dpad Up"))
  }

  @Test
  fun `resolves enum names`() {
    assertEquals(KeyCode.VOLUME_UP, KeyPressAgentAction.resolveKeyCode("VOLUME_UP"))
    assertEquals(KeyCode.REMOTE_CENTER, KeyPressAgentAction.resolveKeyCode("REMOTE_CENTER"))
    assertEquals(KeyCode.REMOTE_PLAY_PAUSE, KeyPressAgentAction.resolveKeyCode("remote_play_pause"))
  }

  @Test
  fun `resolves android keyevent style aliases`() {
    assertEquals(KeyCode.BACKSPACE, KeyPressAgentAction.resolveKeyCode("DEL"))
    assertEquals(KeyCode.BACKSPACE, KeyPressAgentAction.resolveKeyCode("DELETE"))
    assertEquals(KeyCode.BACKSPACE, KeyPressAgentAction.resolveKeyCode("KEYCODE_DEL"))
    assertEquals(KeyCode.ESCAPE, KeyPressAgentAction.resolveKeyCode("ESC"))
    assertEquals(KeyCode.REMOTE_CENTER, KeyPressAgentAction.resolveKeyCode("DPAD_CENTER"))
    assertEquals(KeyCode.REMOTE_UP, KeyPressAgentAction.resolveKeyCode("KEYCODE_DPAD_UP"))
  }

  @Test
  fun `trims whitespace`() {
    assertEquals(KeyCode.TAB, KeyPressAgentAction.resolveKeyCode(" TAB "))
  }

  @Test
  fun `returns null for unknown keys`() {
    assertNull(KeyPressAgentAction.resolveKeyCode("F13"))
    assertNull(KeyPressAgentAction.resolveKeyCode(""))
  }
}
