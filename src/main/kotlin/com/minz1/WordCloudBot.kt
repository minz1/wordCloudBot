package com.minz1

import com.jessecorbett.diskord.api.exception.DiscordException
import com.jessecorbett.diskord.api.rest.CreateMessage
import com.jessecorbett.diskord.api.rest.Embed
import com.jessecorbett.diskord.api.rest.EmbedImage
import com.jessecorbett.diskord.api.rest.client.ChannelClient
import com.jessecorbett.diskord.api.rest.client.GuildClient
import com.jessecorbett.diskord.dsl.bot
import com.jessecorbett.diskord.dsl.command
import com.jessecorbett.diskord.dsl.commands
import com.jessecorbett.diskord.util.authorId
import com.jessecorbett.diskord.util.toFileData
import com.kennycason.kumo.CollisionMode
import com.kennycason.kumo.WordCloud
import com.kennycason.kumo.bg.CircleBackground
import com.kennycason.kumo.font.scale.SqrtFontScalar
import com.kennycason.kumo.nlp.FrequencyAnalyzer
import com.kennycason.kumo.palette.ColorPalette
import com.minz1.Messages.messageContent
import com.minz1.Messages.messageId
import com.natpryce.konfig.*
import com.natpryce.konfig.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.experimental.suspendedTransactionAsync
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.Color
import java.awt.Dimension
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class WordCloudBot {
    private val BOT_TOKEN = "bot.token"
    private val CHANNEL_ID = "channel.id"
    private val CMD_PREFIX = "command.prefix"
    private val FILE_NAME = "config/wcbot.properties"
    private val GUILD_ID = "guild.id"

    private val config = ConfigurationProperties.systemProperties() overriding
            EnvironmentVariables() overriding
            ConfigurationProperties.fromFile(File(FILE_NAME))

    private val keyBotToken = Key(BOT_TOKEN, stringType)
    private val keyChannelId = Key(CHANNEL_ID, stringType)
    private val keyCmdPrefix = Key(CMD_PREFIX, stringType)
    private val keyGuildId = Key(GUILD_ID, stringType)

    private val botToken = config[keyBotToken]
    private val channelId = config[keyChannelId]
    private val cmdPrefix = config[keyCmdPrefix]
    private val guildId = config[keyGuildId]

    private val db = Database.connect("jdbc:sqlite:wordcloudbot.db", "org.sqlite.JDBC")
    private val channelClient = ChannelClient(botToken, channelId)
    private val guildClient = GuildClient(botToken, guildId)

    // wordcloud frequency config
    private val frequencyAnalyzer = FrequencyAnalyzer()
    private val stopWords = ("the be to of and a in that have i it for not on with he as you do at this but his by from" +
            " they we say her she or an will my one all would there their what so up out if about who get which go me" +
            " when make can like time no just him know take people into year your good some could them see other than then" +
            " now look only come its over think also back after use two how our work first well way even new want because" +
            " any these give day most us are don't you're was i'm has yes did too why that's thats i'll here had those" +
            " its i'd it's can't got dont were i've what's").split(" ")
    // wordcloud image config
    private val dimension = Dimension(1000, 1000)
    private val background = CircleBackground(500)
    private val fontScalar = SqrtFontScalar(10, 175)

    suspend fun runBot() {
        TransactionManager.manager.defaultIsolationLevel = 8 // Connection.TRANSACTION_SERIALIZABLE = 8

        transaction(db) {
            SchemaUtils.create(Messages)
        }

        frequencyAnalyzer.setWordFrequenciesToReturn(100)
        frequencyAnalyzer.setStopWords(stopWords)

        bot(botToken) {
            commands(cmdPrefix) {
                command("help") {
                    reply {
                        title = "Usage"
                        description = "`\$wordcloud [mention] [timerange]`\n" +
                                "**[mention]**: the person the word cloud is being made for. Say **\"me\"** to generate your own," +
                                " **\"everyone\"** to generate one for the whole server, or mention another user to generate theirs.\n" +
                                "**[timeRange]**: the time range for the word cloud.\n" +
                                "Options are: **(day/week/month/alltime)**"
                    }
                }

                command("wordcloud") {
                    loadLatestMessages()

                    val args = this.content.removePrefix("\$wordcloud").trim().replace(" +".toRegex(), " ").split(" ")

                    if (args.size != 2) {
                        reply {
                            title = "Error!"
                            description = "You must supply 2 (two) arguments to this command!\n" +
                                    "**The first argument:** the person the word cloud is being made for. Say **\"me\"** to generate your own," +
                                    " **\"everyone\"** to generate one for the whole server, or mention another user to generate theirs.\n" +
                                    "**The second argument:** the time range for the word cloud.\n" +
                                    "    Options are: **(day/week/month/alltime)**"
                        }
                        return@command
                    }

                    val target = args[0].toLowerCase()

                    val targetId = when (target) {
                        "me" -> authorId
                        "everyone" -> authorId
                        else -> target.replace("[^\\d]".toRegex(), "")
                    }


                    val targetMember = try {
                        guildClient.getMember(targetId)
                    } catch (de: DiscordException) {
                        reply {
                            title = "Error!"
                            description = "The person you specified is invalid!\n" +
                                    "This argument MUST be a mention of a user, \"me\" for yourself, or \"everyone\" for everybody!"
                        }
                        return@command
                    }

                    val daysAgo = when(args[1]) {
                        "day" -> 1
                        "week" -> 7
                        "month" -> 31
                        "alltime" -> 0
                        else -> -1
                    }

                    if (daysAgo == -1) {
                        reply {
                            title = "Error!"
                            description = "The time you specified is invalid!\n" +
                                    "Please select one of these times: (day/week/month/alltime)"
                        }
                        return@command
                    }

                    val allUserMessages = suspendedTransactionAsync(Dispatchers.IO) {
                        val query = Messages.slice(messageContent).selectAll()

                        if (target == "everyone") {
                            if (daysAgo != 0) {
                                query.adjustWhere {
                                    Messages.timeStamp.greaterEq(getDaysAgo(daysAgo))
                                }
                            }
                        } else {
                            if (daysAgo == 0) {
                                query.adjustWhere {
                                    Messages.authorId eq targetId
                                }
                            } else {
                                query.adjustWhere {
                                    Messages.authorId eq targetId and Messages.timeStamp.greaterEq(getDaysAgo(daysAgo))
                                }
                            }
                        }

                        query.map { it[messageContent] }
                    }.await()

                    val wcImageName = "${randomString(8)}.png"
                    val wcImage = File(wcImageName)
                    val wordFrequencies = withContext(Dispatchers.IO) { frequencyAnalyzer.load(allUserMessages) }
                    val colorPalette = ColorPalette(generateRandomColor(), generateRandomColor(), generateRandomColor(), generateRandomColor() , generateRandomColor(), generateRandomColor())
                    val wordCloud = WordCloud(dimension, CollisionMode.PIXEL_PERFECT)

                    wordCloud.setPadding(2)
                    wordCloud.setBackground(background)
                    wordCloud.setColorPalette(colorPalette)
                    wordCloud.setFontScalar(fontScalar)
                    wordCloud.build(wordFrequencies)
                    wordCloud.writeToFile(wcImageName)

                    val wordCloudSource = when (target) {
                        "everyone" -> "This server"
                        else -> "${targetMember.nickname ?: targetMember.user?.username ?: "Your selected user"}"
                    }

                    channel.createMessage(CreateMessage(content = "$wordCloudSource's word cloud",
                            embed = Embed(image = EmbedImage(url = "attachment://$wcImageName"))), wcImage.toFileData())

                    wcImage.delete()
                }
            }
        }
    }

    private fun randomString(length: Int): String {
        return (1..length)
                .map { "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".random() }
                .joinToString("")
    }

    private suspend fun loadLatestMessages() {
        var cutOff = false
        var messages = channelClient.getMessages(100).toMutableList()

        do {
            val messageIds = messages.map { it.id }

            val messagesWithOccurrences = suspendedTransactionAsync(Dispatchers.IO) {
                Messages.slice(messageId, messageId.count())
                        .select { messageId inList messageIds }
                        .groupBy(messageId)
                        .map { it[messageId] to it[messageId.count()]}
                        .toMap()
            }.await()


            if (messagesWithOccurrences.isNotEmpty()) {
                for (messageId in messageIds) {
                    val occurrences = messagesWithOccurrences[messageId]

                    if (occurrences != null && occurrences > 0) {
                        cutOff = true
                        val cutOffIndex = messageIds.indexOf(messageId)
                        messages.subList(cutOffIndex, messages.size).clear()
                        break
                    }
                }
            }

            newSuspendedTransaction(Dispatchers.IO) {
                for (message in messages) {
                    Message.new {
                        channelId = message.channelId
                        authorId = message.authorId
                        messageId = message.id
                        messageContent = message.content
                        timeStamp = LocalDateTime.parse(message.sentAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    }
                }
            }

            if (! cutOff) {
                val lastMessageId = messages.last().id
                messages = channelClient.getMessagesBefore(100, lastMessageId).toMutableList()
                delay(1000)
            }
        } while (messages.isNotEmpty() && !cutOff)
    }

    private fun generateRandomColor(): Color {
        val hue = Math.random().toFloat()
        val saturation = 0.75f + (Math.random().toFloat() * 0.25f)
        val luminance = 0.9f + (Math.random().toFloat() * 0.1f)

        return Color.getHSBColor(hue, saturation, luminance)
    }

    private fun getDaysAgo(daysAgo: Int): LocalDateTime {
        return LocalDateTime.now().minusDays(daysAgo.toLong())
    }
}