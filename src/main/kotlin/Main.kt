import com.kotlindiscord.kord.extensions.DISCORD_BLURPLE
import com.kotlindiscord.kord.extensions.ExtensibleBot
import com.kotlindiscord.kord.extensions.checks.failed
import com.kotlindiscord.kord.extensions.checks.memberFor
import com.kotlindiscord.kord.extensions.checks.nullMember
import com.kotlindiscord.kord.extensions.checks.passed
import com.kotlindiscord.kord.extensions.checks.types.Check
import com.kotlindiscord.kord.extensions.utils.runSuspended
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import com.kotlindiscord.kord.extensions.utils.scheduling.Task
import com.mongodb.client.MongoCollection
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Member
import dev.kord.core.entity.Role
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.core.event.guild.GuildCreateEvent
import dev.kord.core.event.guild.GuildDeleteEvent
import dev.kord.core.event.guild.MemberJoinEvent
import dev.kord.core.event.guild.MemberLeaveEvent
import dev.kord.core.sorted
import dev.kord.gateway.Intent
import dev.kord.gateway.Intents
import dev.kord.gateway.PrivilegedIntent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import mu.KotlinLogging
import org.litote.kmongo.KMongo
import org.litote.kmongo.*
import org.litote.kmongo.getCollection
import java.sql.Time
import java.time.Instant
import kotlin.time.Duration

val TOKEN = System.getenv("TOKEN") ?: error("Please add TOKEN to your environmental variables")
val connectURI = System.getenv("CONNECT_URI") ?: error("Please add a mongodb uri to connect with your database")
val dbclient = KMongo.createClient(connectURI)
val db = dbclient.getDatabase("CommunityBot")
val database = db.getCollection<Server>("Community")


const val DEFAULT_PREFIX = "!"

data class Server(val id: String, var prefix: String = DEFAULT_PREFIX, val community: MutableList<CommunityMember> = mutableListOf()) {
    val shop = Shop(mutableListOf())
}
data class CommunityMember(val id: String, var money: Int = 100, val items: MutableList<Item> = mutableListOf<Item>(), var lastWork: Instant? = null)

enum class Type(val type: String) {
    Role("role"),
    Item("item"),
    All("all")
}
data class Shop(val items: MutableList<Item>)
data class Item(val type: Type,
                val id: String,
                val name: String,
                val description: String = "",
                val price: Int = 100,
                var isLimited: Boolean = false,
                var limitCount: Int = 0)

val cache = mutableMapOf<String, Server>()
lateinit var scheduler: Task
@PrivilegedIntent
suspend fun main() {
    val bot = ExtensibleBot(TOKEN) {
        chatCommands {
            defaultPrefix = "!"
            invokeOnMention = true
            enabled = true
           prefix { defaultPrefix ->
               cache[guildId?.asString]?.prefix ?: defaultPrefix
           }
        }

        applicationCommands {
            this.enabled = true
            defaultGuild(503652829685088276)
            //defaultGuild(888037056293519410)

        }

        intents {
            +Intents.all
        }

        members {
            fillPresences = true
            all()
        }

        extensions {
            help {
                enableBundledExtension = true
                colour {
                    when (guildId) {
                        null ->
                            DISCORD_BLURPLE
                        else -> message.getAuthorAsMember()?.color() ?: error("Couldn't get member from this message")
                    }
                }
                pingInReply = false

                deleteInvocationOnPaginatorTimeout = !true
                deletePaginatorOnTimeout = false
            }
            add(::CommunityCommands)
            add(::ShopCommands)
            add(::Tests)
        }

        scheduler = Scheduler().schedule(60, name = "readDB", ) {
            for ((key, _) in cache) {
                cache[key] = database.findOne(Server::id eq key)!!
            }
            restart(scheduler)
        }
    }

    // When bot is launched
    bot.on<ReadyEvent>() {
        for (guild in guilds) {
            val ser = try {
                println("I know this guild: ${guild.id.asString}")
                database.findOne { Server::id eq guild.id.asString } ?: error("Server not found in the database")
            } catch (e: Exception) {
                println("I added this guild: ${guild.id.asString}")
                val members = guild.members.toList().filter { !it.isBot }
                val cmembers = mutableListOf<CommunityMember>()
                for (member in members) {
                    with(member) {
                        cmembers.add(CommunityMember(id.asString))
                    }
                }
                Server(guild.id.asString, DEFAULT_PREFIX, cmembers)

            }

            try {
                database.write(ser.id, ser)
                cache[guild.id.asString] = ser
            } catch (e: Exception) {
                println(e.message ?: e.cause ?: e)
            }
        }
    }

    // When bot is added to a new guild
    bot.on<GuildCreateEvent> {
        try {
            val a = database.findOne(Server::id eq guild.id.asString)
        } catch (e: Exception) {
            println("Added to a new guild: ${guild.id.asString}")
            val members = guild.members.toList().filter { !it.isBot }
            val cmembers = mutableListOf<CommunityMember>()
            for (member in members) {
                with(member) {
                    cmembers.add(CommunityMember(id.asString, 0))
                }
            }
            val ser = Server(guild.id.asString, DEFAULT_PREFIX, cmembers)
            cache[ser.id] = ser
            database.write(ser.id, ser)
        }
    }
    // When bot is removed from a guild
    bot.on<GuildDeleteEvent> {
        println("Removed from guild: ${guild!!.id.asString}")
        val ser = cache[guildId.asString] ?: error("Guild not found")
        database.deleteOne(Server::id eq ser.id)
        cache.remove(ser.id)
    }

    // When a new member joins the guild
    bot.on<MemberJoinEvent> {
        val ser = cache[guildId.asString] ?: error("Guild not found")
        with(ser) {
            if (!member.isBot)
                community.add(CommunityMember(member.id.asString))
        }
        cache[ser.id] = ser
        database.write(ser.id, ser)
    }
    // When a member leaves the guild
    bot.on<MemberLeaveEvent> {
        val ser = cache[guildId.asString] ?: error("Guild not found")
        ser.community.removeIf { it.id == user.id.asString }
        cache[ser.id] = ser
        database.write(ser.id, ser)
    }




    bot.start()
}

/**
 * Returns the color of a member (defaults to blurple)
 */
suspend fun Member.color(): Color {
    return roles.sorted().toList().reversed().firstOrNull { it.color.rgb != 0 }?.color ?: Color(7506394)
}

/**
 * Either add or update a server
 *
 * [id]  -> The id of the guild you'd like to update
 *
 * [ser] -> The new server
 */
internal fun <TDocument> MongoCollection<TDocument>.write(id: String, ser: Server) {
    val serv = database.findOne { Server::id eq id }
    if (serv == null) {
        database.insertOne(ser)
    } else {
        database.updateOne(Server::id eq id, ser)
    }
}

/**
 * Check if the user is the bot owner or not
 *
 * [memberId] -> The member's ID
 */
public fun botOwner(memberId: Long): Check<*> = {
    val logger = KotlinLogging.logger("com.kotlindiscord.kord.extensions.checks.botOwner")
    val member = memberFor(event)

    if (member == null) {
        logger.nullMember(event)
        pass()

    } else {
        val memberObj = member.asMember()

        val result = when (memberObj.id) {
            Snowflake(memberId) -> true
            else -> false
        }

        if (result) {
            logger.failed("Member $member is the bot owner")

        } else {
            logger.passed()
            pass()
        }
    }
}
/**
 * Check if the user is the bot owner or not
 *
 * [memberId] -> The member's ID
 */
public fun botOwner(memberId: String): Check<*> = {
    val logger = KotlinLogging.logger("com.kotlindiscord.kord.extensions.checks.botOwner")
    val member = memberFor(event)

    if (member == null) {
        logger.nullMember(event)
        pass()

    } else {
        val memberObj = member.asMember()

        val result = when (memberObj.id) {
            Snowflake(memberId) -> true
            else -> false
        }

        if (result) {
            logger.failed("Member $member is the bot owner")

        } else {
            logger.passed()
            pass()
        }
    }
}
/**
 * Check if the user is the bot owner or not
 *
 * [memberId] -> The member's ID
 */
public fun botOwner(memberId: Snowflake): Check<*> = {
    val logger = KotlinLogging.logger("com.kotlindiscord.kord.extensions.checks.botOwner")
    val member = memberFor(event)

    if (member == null) {
        logger.nullMember(event)
        pass()

    } else {
        val memberObj = member.asMember()

        val result = when (memberObj.id) {
            memberId -> true
            else -> false
        }

        if (result) {
            logger.failed("Member $member is the bot owner")

        } else {
            logger.passed()
            pass()
        }
    }
}

/**
 * Restart the scheduler
 *
 * [scheduler] -> The task you'd like to restart
 */
fun restart(task: Task) {
    task.restart()
}