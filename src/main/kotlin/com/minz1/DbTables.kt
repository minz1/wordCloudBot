package com.minz1

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.`java-time`.datetime
import java.time.LocalDateTime

object Messages : IntIdTable() {
    val channelId: Column<String> = varchar("channelid", length=32)
    val authorId: Column<String> = varchar("authorid", length=32)
    val messageId: Column<String> = varchar("messageid", length=32)
    val messageContent: Column<String> = varchar("messagecontent", length=2000)
    val timeStamp: Column<LocalDateTime> = datetime("timestamp")
}

class Message(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Message>(Messages)
    var channelId by Messages.channelId
    var authorId by Messages.authorId
    var messageId by Messages.messageId
    var messageContent by Messages.messageContent
    var timeStamp by Messages.timeStamp // ISO8601
}