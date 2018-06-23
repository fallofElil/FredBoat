package fredboat.test.sentinel

import com.fredboat.sentinel.SentinelExchanges
import com.fredboat.sentinel.entities.*
import fredboat.perms.Permission
import fredboat.sentinel.RawGuild
import fredboat.sentinel.RawMember
import fredboat.sentinel.RawTextChannel
import fredboat.sentinel.RawVoiceChannel
import fredboat.test.sentinel.SentinelState.outgoing
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.annotation.RabbitHandler
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private lateinit var rabbit: RabbitTemplate

/** State of the fake Rabbit client */
object SentinelState {
    var guild = DefaultSentinelRaws.guild
    val outgoing = mutableMapOf<Class<*>, LinkedBlockingQueue<Any>>()
    private val log: Logger = LoggerFactory.getLogger(SentinelState::class.java)

    fun reset() {
        guild = DefaultSentinelRaws.guild.copy()
        outgoing.clear()
        rabbit.convertSendAndReceive(SentinelExchanges.EVENTS, GuildUpdateEvent(DefaultSentinelRaws.guild))
    }

    fun <T> poll(type: Class<T>, timeoutMillis: Long = 5000): T? {
        val queue = outgoing.getOrPut(type) { LinkedBlockingQueue() }
        @Suppress("UNCHECKED_CAST")
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) as? T
    }

    fun joinChannel(
            member: RawMember = DefaultSentinelRaws.owner,
            channel: RawVoiceChannel = DefaultSentinelRaws.musicChannel
    ) {
        val newList = guild.voiceChannels.toMutableList().apply {
            removeIf { it == channel }
            val membersSet = channel.members.toMutableSet()
            membersSet.add(member.id)
            add(channel.copy(members = membersSet.toList()))
        }
        guild = guild.copy(voiceChannels = newList)
        guild = setMember(guild, member.copy(voiceChannel = channel.id))
        rabbit.convertSendAndReceive(SentinelExchanges.EVENTS, VoiceJoinEvent(
                DefaultSentinelRaws.guild.id,
                channel.id,
                member.id))

        log.info("Emulating ${member.name} joining ${channel.name}")
    }

    private fun setMember(guild: RawGuild, member: RawMember): RawGuild {
        return guild.copy(members = guild.members.toMutableSet().apply { add(member) }.toList())
    }
}

@Service
@Suppress("MemberVisibilityCanBePrivate")
@RabbitListener(queues = [SentinelExchanges.REQUESTS])
class MockSentinelRequestHandler(template: RabbitTemplate) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(MockSentinelRequestHandler::class.java)
    }

    init {
        rabbit = template
    }

    @RabbitHandler
    fun subscribe(request: GuildSubscribeRequest): RawGuild {
        return SentinelState.guild
    }

    @RabbitHandler
    fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        default(request)
        log.info("FredBoat says: ${request.message}")
        return SendMessageResponse(Math.random().toLong())
    }

    @RabbitHandler
    fun editMessage(request: EditMessageRequest): SendMessageResponse {
        default(request)
        log.info("FredBoat edited: ${request.message}")
        return SendMessageResponse(request.messageId)
    }

    @RabbitHandler(isDefault = true)
    fun default(request: Any) {
        val queue = outgoing.getOrPut(request.javaClass) { LinkedBlockingQueue() }
        queue.put(request)
    }
}

/** Don't use immutable lists here. We want to be able to modify state directly */
@Suppress("MemberVisibilityCanBePrivate")
object DefaultSentinelRaws {
    val owner = RawMember(
            81011298891993088,
            "Fre_d",
            "Fred",
            "0310",
            174820236481134592,
            false,
            mutableListOf(),
            null
    )

    val self = RawMember(
            152691313123393536,
            "FredBoat♪♪",
            "FredBoat",
            "7284",
            174820236481134592,
            true,
            mutableListOf(),
            null
    )

    val generalChannel = RawTextChannel(
            174820236481134592,
            "general",
            (Permission.MESSAGE_READ + Permission.MESSAGE_WRITE).raw
    )

    val privateChannel = RawTextChannel(
            184358843206074368,
            "private",
            0
    )

    val musicChannel = RawVoiceChannel(
            226661001754443776,
            "Music",
            mutableListOf(),
            5,
            (Permission.VOICE_CONNECT + Permission.VOICE_SPEAK).raw
    )

    val guild = RawGuild(
            174820236481134592,
            "FredBoat Hangout",
            owner.id,
            mutableListOf(owner, self),
            mutableListOf(generalChannel, privateChannel),
            mutableListOf(musicChannel),
            mutableListOf()
    )
}