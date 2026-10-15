package com.bingo.multiplayer.domain.repository

import android.content.SharedPreferences
import com.bingo.multiplayer.domain.model.AuthState
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReliableMatchStatisticsTest {
    @Test fun resultHistoryAndCareerAreAtomicAndIdempotentAcrossRepositoryRestart() {
        val backing = AuthRepositoryTest.FakeSharedPreferences()
        backing.edit().putString("uid","google_statistics").putString("display_name","Kevin").putString("username","statistics_test").commit()
        var fail = false
        val prefs = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val delegate = backing.edit()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit" && fail) {
                        // Model Android's memory update despite a failed disk commit.
                        method.invoke(delegate, *(args ?: emptyArray()))
                        false
                    }
                    else {
                        val result = method.invoke(delegate, *(args ?: emptyArray()))
                        if (method.returnType == SharedPreferences.Editor::class.java) proxy else result
                    }
                } as SharedPreferences.Editor
            }
        }
        val repository = AuthRepository(customPrefs=prefs)
        assertFalse(repository.recordMatch("Online Match","Stuart",true,5,stableResultId="wrong-account",expectedAccountUid="another-account"))
        assertEquals(0,backing.getInt("games_played",0))
        fail = true
        assertFalse(repository.recordMatch("Online Match","Stuart",true,5,stableResultId="final-1"))
        assertEquals(0,backing.getInt("games_played",0)); assertTrue(repository.getMatchHistory().isEmpty())
        fail = false
        assertTrue(repository.recordMatch("Online Match","Stuart",true,5,stableResultId="final-1"))
        assertEquals(1,backing.getInt("games_played",0)); assertEquals(1,repository.getMatchHistory().size)
        val restarted = AuthRepository(customPrefs=prefs)
        assertTrue(restarted.recordMatch("Online Match","Stuart",true,5,stableResultId="final-1"))
        assertEquals(1,backing.getInt("games_played",0)); assertEquals(1,restarted.getMatchHistory().size)
        assertEquals(1,(restarted.authState.value as AuthState.Authenticated).user.gamesWon)
        repository.sessionManager.stopSessionWatcher(); restarted.sessionManager.stopSessionWatcher()
    }
}
