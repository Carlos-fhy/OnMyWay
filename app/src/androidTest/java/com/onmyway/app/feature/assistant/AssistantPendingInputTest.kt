package com.onmyway.app.feature.assistant

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantPendingInputTest {
    @Test fun spokenCancelAndConfirmHandlePendingDrafts() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("assistant_pending_input_test", 0)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }
        val chat = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }
        try {
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { chat.ready }) delay(20) }
            withContext(Dispatchers.Main) {
                chat.proposal = JSONObject().put("action", "plan_route")
                    .put("destination", "图书馆").put("mode", "")
                chat.input = "取消"
                chat.send(vm)
                assertEquals("", chat.input)
            }
            // Cancellation only clears the visible card after its Room transaction completes.
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { chat.busy } || db.assistant().draft() != null) delay(20)
            }
            withContext(Dispatchers.Main) {
                assertNull(chat.proposal)
                chat.proposal = JSONObject().put("action", "create_task")
                    .put("title", "打印论文").put("place_type", "PRINT_SHOP")
                chat.input = "确认"
                chat.send(vm)
            }
            withTimeout(5_000) {
                while (db.tasks().observe().first().none { it.title == "打印论文" }) delay(20)
            }
            // The Room transaction becomes visible before the confirming coroutine refreshes the card.
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { chat.busy }) delay(20)
            }
            assertEquals("", withContext(Dispatchers.Main) { chat.error })
            assertNull(withContext(Dispatchers.Main) { chat.proposal })
            assertEquals("打印论文", db.tasks().observe().first().single().title)
        } finally {
            withContext(Dispatchers.Main) {
                chat.viewModelScope.cancel()
                vm.viewModelScope.cancel()
            }
            db.close()
            prefs.edit().clear().commit()
        }
    }
}
