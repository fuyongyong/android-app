package one.mixin.android.db

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Query
import one.mixin.android.vo.App

@Dao
interface AppDao : BaseDao<App> {

    @Query("SELECT a.* FROM apps a, participants p, users u WHERE p.conversation_id = :conversationId" +
        " AND p.user_id = u.user_id AND a.app_id = u.app_id")
    fun getGroupConversationApp(conversationId: String): LiveData<List<App>>

    @Query("SELECT a.* FROM apps a, users u WHERE u.user_id = :userId AND a.app_id = u.app_id")
    fun getConversationApp(userId: String?): LiveData<List<App>>

    @Query("SELECT * FROM apps WHERE app_id = :id")
    fun findAppById(id: String): App?
}