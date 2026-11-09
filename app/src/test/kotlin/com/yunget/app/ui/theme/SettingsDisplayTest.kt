package com.yunget.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDisplayTest {
    @Test fun conciseModeHidesHelpWithoutLosingSelectedValue() {
        assertEquals("16 条连接", settingSupportingText("引擎按吞吐自动调整", "16 条连接", false))
        assertEquals("Download", settingSupportingText("新任务保存到此目录", "Download", false))
    }

    @Test fun explanationOnlyRowsHaveNoEmptySupportingSpace() {
        assertNull(settingSupportingText("选择主题、字号和动效", null, false))
        assertNull(settingSupportingText("", "  ", false))
    }

    @Test fun detailedModeRestoresExplanation() {
        assertEquals("引擎按吞吐自动调整", settingSupportingText("引擎按吞吐自动调整", "16 条连接", true))
    }
}
