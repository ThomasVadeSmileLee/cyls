package org.smileLee.cyls

import com.alibaba.fastjson.*
import com.scienjus.smartqq.callback.*
import com.scienjus.smartqq.client.*
import com.scienjus.smartqq.constant.*
import com.scienjus.smartqq.model.*
import org.ansj.splitWord.analysis.*
import org.smileLee.cyls.cyls.*
import org.smileLee.cyls.util.*
import org.smileLee.cyls.util.Util.TimeFormat
import org.smileLee.cyls.util.Util.itemByChance
import org.smileLee.cyls.util.Util.randomInt
import org.smileLee.cyls.util.Util.runByChance
import org.smileLee.cyls.util.Util.sign
import org.smileLee.cyls.util.Util.timeFrom
import org.smileLee.cyls.util.Util.timeOf
import org.smileLee.cyls.util.Util.tomorrowName
import sun.dc.path.*
import java.io.*
import java.lang.Thread.*
import java.util.*

/**
 * @author 2333
 */
object Main {
    val MAX_RETRY = 3
    private inline fun <T> retry(action: () -> T): T {
        for (retry in 0..MAX_RETRY) {
            try {
                return action()
            } catch (e: Exception) {
                if (retry != MAX_RETRY) {
                    println("[${Util.timeName}] 第${retry + 1}次尝试失败。正在重试...")
                }
            }
        }
        println("[${Util.timeName}] 重试次数达到最大限制，程序无法继续进行。")
        System.exit(1)
        throw Error("Unreachable code")
    }

    private var working = true

    var data = Data()

    var currentGroupMessage: GroupMessage = GroupMessage()
    private var currentFriendMessage: Message = Message()
    val currentGroupId get() = currentGroupMessage.groupId
    val currentGroup get() = data._cylsGroupFromId[currentGroupMessage.groupId]!!
    private val currentUser get() = data._cylsFriendFromId[currentGroupMessage.userId]!!
    val currentFriend get() = data._cylsFriendFromId[currentFriendMessage.userId]!!

    fun reply(message: String) {
        println("[${Util.timeName}] [${currentGroup.name}] > $message")
        client.sendMessageToGroup(currentGroupId, message)
    }

    fun replyToFriend(message: String) {
        println("[${Util.timeName}] [${currentFriend.markName}] > $message")
        client.sendMessageToFriend(currentFriendMessage.userId, message)
    }

    /**
     * SmartQQ客户端
     */
    lateinit var client: SmartQQClient
    val callback = object : MessageCallback {
        override fun onMessage(message: Message) {
            if (working) {
                try {
                    println("[${Util.timeName}] [私聊] ${getFriendNick(message.userId)}：${message.content}")
                    currentFriendMessage = message
                    currentGroup
                    currentFriend
                    if (message.content.startsWith("cyls.")) try {
                        val order = Util.readOrder(message.content.substring(5))
                        currentFriend.status.commandTree.findPath(order.path).run(order.message)
                    } catch (e: PathException) {
                        reply("请确保输入了正确的指令哦|•ω•`)")
                    } else {
                        if (!currentUser.isIgnored) {
                            if (currentUser.isRepeated) {
                                runByChance(currentUser.repeatFrequency) {
                                    reply(message.content)
                                }
                            } else {
                                currentFriend.status.replyVerifier.findAndRun(message.content)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }

        override fun onGroupMessage(message: GroupMessage) {
            if (working) {
                try {
                    println("[${Util.timeName}] [${getGroupName(message.groupId)}] " +
                            "${getGroupUserNick(message.groupId, message.userId)}：${message.content}")
                    currentGroupMessage = message
                    currentGroup
                    currentUser
                    if (message.content.startsWith("cyls.")) try {
                        val order = Util.readOrder(message.content.substring(5))
                        groupMainRoot.findPath(order.path).run(order.message)
                    } catch (e: PathException) {
                        reply("请确保输入了正确的指令哦|•ω•`)")
                    } else {
                        if (!currentGroup.isPaused && !currentUser.isIgnored && !currentGroup.hot
                                && getGroupUserNick(message.groupId, message.userId) != "系统消息") {
                            currentGroup.addMessage()
                            if (currentUser.isRepeated) {
                                runByChance(currentUser.repeatFrequency) {
                                    reply(message.content)
                                }
                            } else if (currentGroup.isRepeated) {
                                runByChance(currentGroup.repeatFrequency) {
                                    reply(message.content)
                                }
                            } else {
                                groupMainVerifier.findAndRun(message.content)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    val savedFileName = "cylsData/savedFile.txt"
    val savedFile = File(savedFileName)
    val qrCodeFileName = "cylsData/qrcode.png"
    val qrCodeFile = File(qrCodeFileName)

    /**
     * 加载群信息等
     */
    fun load() {
        val file = savedFile
        val fin = FileInputStream(file)
        val length = fin.available()
        val bytes = ByteArray(length)
        fin.read(bytes)
        val json = String(bytes)
        data = JSON.parseObject(json, Data::class.java)
        working = false   //映射建立完毕前暂停接收消息以避免NullPointerException
        println()
        println("[${Util.timeName}] 开始建立索引，暂停接收消息")
        println("[${Util.timeName}] 尝试建立好友列表索引...")
        val friendList = retry { client.friendList }
        retry {
            friendList.forEach { friend ->
                data.cylsFriendList.filter { cylsFriend -> cylsFriend.markName == friend.markname }
                        .forEach { cylsFriend -> cylsFriend.set(friend) }
                data.cylsFriendFromId[friend.userId].set(friend)
            }
        }
        println("[${Util.timeName}] 建立好友列表索引成功。")
        println("[${Util.timeName}] 尝试建立群列表索引...")
        val groupList = retry { client.groupList }
        retry {
            groupList.forEach { group ->
                data.cylsGroupList.filter { cylsGroup -> cylsGroup.name == group.name }
                        .forEach { cylsGroup -> cylsGroup.set(group) }
                data.cylsGroupFromId[group.id].set(group)
            }
        }
        println("[${Util.timeName}] 建立群列表索引成功。")
        data.cylsFriendList.filter { it.markName == "smileLee" }
                .forEach { it.adminLevel = CylsFriend.AdminLevel.OWNER }
        //为防止请求过多导致服务器启动自我保护
        //群id到群详情映射 和 讨论组id到讨论组详情映射 将在第一次请求时创建
        println("[${Util.timeName}] 索引建立完毕，开始接收消息\n")
        working = true                                     //映射建立完毕后恢复工作
    }

    /**
     * 储存群信息等
     */
    fun save() {
        val json = JSON.toJSON(data)
        val file = savedFile
        if (file.exists()) file.delete()
        val fout = FileOutputStream(file)
        fout.write(json.toString().toByteArray())
        fout.close()
    }

    /**
     * 获取群id对应群详情

     * @param id 被查询的群id
     * *
     * @return 该群详情
     */
    fun getGroupInfoFromID(id: Long): GroupInfo {
        val cylsGroup = data.cylsGroupFromId[id]
        return if (cylsGroup.groupInfo != null) cylsGroup.groupInfo!! else {
            val groupInfo = client.getGroupInfo(cylsGroup.group?.code ?: throw RuntimeException())
            cylsGroup.groupInfo = groupInfo
            groupInfo
        }
    }

    /**
     * 获取群消息所在群名称

     * @param id 被查询的群消息
     * *
     * @return 该消息所在群名称
     */
    private fun getGroupName(id: Long): String {
        return getGroup(id).name
    }

    /**
     * 获取群消息所在群

     * @param id 被查询的群消息
     * *
     * @return 该消息所在群
     */
    private fun getGroup(id: Long): CylsGroup {
        return data.cylsGroupFromId[id]
    }

    /**
     * 获取私聊消息发送者昵称

     * @param id 被查询的私聊消息
     * *
     * @return 该消息发送者
     */
    private fun getFriendNick(id: Long): String {
        val user = data.cylsFriendFromId[id].friend
        return user?.markname ?: user?.nickname ?: null!!
    }

    fun getGroupUserNick(gid: Long, uid: Long): String {
        getGroupInfoFromID(gid)
        val user = data.cylsGroupFromId[gid].groupUsersFromId[uid]
        return user.card ?: user.nick
    }

    private val weatherKey = "3511aebb46e04a59b77da9b1c648c398"               //天气查询密钥
    private val weatherUrl = ApiURL("https://free-api.heweather.com/v5/forecast?city={1}&key={2}", "")

    /**
     * @param cityName 查询的城市名
     * @param d        0=今天 1=明天 2=后天
     */
    fun getWeather(cityName: String, d: Int) {
        val actualCityName = cityName.replace("[ 　\t\n]".toRegex(), "")
        if (actualCityName == "") {
            reply("请输入城市名称进行查询哦|•ω•`)")
        } else {
            val days = arrayOf("今天", "明天", "后天")
            reply("云裂天气查询服务|•ω•`)\n下面查询$actualCityName${days[d]}的天气:")
            var msg = ""
            val web = weatherUrl.buildUrl(actualCityName, weatherKey)
            try {
                val result = WebUtil.request(web)
                val weather = JSON.parseObject(result)
                val weatherData = weather.getJSONArray("HeWeather5").getJSONObject(0)
                val basic = weatherData.getJSONObject("basic")
                if (basic == null) {
                    reply("啊呀，真抱歉，查询失败的说，请确认这个地名是国内的城市名……|•ω•`)")
                } else {
                    val forecast = weatherData.getJSONArray("daily_forecast")
                    val day = forecast.getJSONObject(d)
                    val cond = day.getJSONObject("cond")
                    if (cond.getString("txt_d") == cond.getString("txt_n")) {
                        msg += "全天${cond.getString("txt_d")},\n"
                    } else {
                        msg += "白天${cond.getString("txt_d")}，夜晚${cond.getString("txt_n")}，\n"
                    }
                    val tmp = day.getJSONObject("tmp")
                    msg += "最高温${tmp.getString("max")}℃，最低温${tmp.getString("min")}℃，\n"
                    val wind = day.getJSONObject("wind")
                    if (wind.getString("sc") == "微风") msg += "微${wind.getString("dir")}|•ω•`)"
                    else msg += "${wind.getString("dir")}${wind.getString("sc")}级|•ω•`)"
                    reply(msg)
                }
            } catch(e: Exception) {
                reply("啊呀，真抱歉，查询失败的说，请确认这个地名是国内的城市名……|•ω•`)")
            }
        }
    }

    val groupMainRoot = createTree {
        childNode("sudo", {
            reply("""输入
cyls.help.sudo
查看帮助信息|•ω•`)""")
        }) {
            childNode("ignore", {
                val uin = it.toLong()
                val destUser = data._cylsFriendFromId[uin] ?: {
                    reply("未找到此人哦|•ω•`)")
                    null!!
                }()
                if (currentUser.isOwner || (currentUser.isAdmin && !destUser.isAdmin)) {
                    if (!destUser.isIgnored) destUser.ignoreLevel = CylsFriend.IgnoreLevel.IGNORED
                    reply("${getGroupUserNick(currentGroupId, uin)}已被屏蔽，然而这么做是不是不太好…… |•ω•`)")
                    save()
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            })
            childNode("recognize", {
                val uin = it.toLong()
                val destUser = data._cylsFriendFromId[uin] ?: {
                    reply("未找到此人哦|•ω•`)")
                    null!!
                }()
                if (currentUser.isOwner || (currentUser.isAdmin && !destUser.isAdmin)) {
                    if (destUser.isIgnored) destUser.ignoreLevel = CylsFriend.IgnoreLevel.RECOGNIZED
                    reply("${getGroupUserNick(currentGroupId, uin)}已被解除屏蔽，当初为什么要屏蔽他呢…… |•ω•`)")
                    save()
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            })
            childNode("authorize", {
                val uin = it.toLong()
                val destUser = data._cylsFriendFromId[uin] ?: {
                    reply("未找到此人哦|•ω•`)")
                    null!!
                }()
                if (currentUser.isOwner) {
                    if (!destUser.isAdmin) {
                        destUser.adminLevel = CylsFriend.AdminLevel.ADMIN
                        reply("${getGroupUserNick(currentGroupId, uin)}已被设置为管理员啦 |•ω•`)")
                        save()
                    } else {
                        reply("${getGroupUserNick(currentGroupId, uin)}已经是管理员了， 再设置一次有什么好处么…… |•ω•`)")
                    }
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧|•ω•`)")
                }
            })
            childNode("unauthorize", {
                val uin = it.toLong()
                val destUser = data._cylsFriendFromId[uin] ?: {
                    reply("未找到此人哦|•ω•`)")
                    null!!
                }()
                if (currentUser.isOwner) {
                    if (destUser.isOwner) {
                        reply("${getGroupUserNick(currentGroupId, uin)}是云裂的主人哦，不能被取消管理员身份…… |•ω•`)")
                    } else if (destUser.isAdmin) {
                        currentUser.adminLevel = CylsFriend.AdminLevel.NORMAL
                        reply("${getGroupUserNick(currentGroupId, uin)}已被取消管理员身份……不过，真的要这样么 |•ω•`)")
                        save()
                    } else {
                        reply("${getGroupUserNick(currentGroupId, uin)}并不是管理员啊，主人你是怎么想到这么做的啊…… |•ω•`)")
                    }
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧 |•ω•`)")
                }
            })
            childNode("pause", {
                if (currentUser.isAdmin) {
                    if (!currentGroup.isPaused) {
                        println(2333)
                        reply("通讯已中断（逃 |•ω•`)")
                        currentGroup.isPaused = true
                        save()
                    } else {
                        reply("已处于中断状态了啊……不能再中断一次了 |•ω•`)")
                    }
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            })
            childNode("resume", {
                if (currentUser.isAdmin) {
                    if (currentGroup.isPaused) {
                        reply("通讯恢复啦 |•ω•`)")
                        currentGroup.isPaused = false
                        save()
                    } else {
                        reply("通讯并没有中断啊，为什么要恢复呢 |•ω•`)")
                    }
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            })
            childNode("repeat", {
                if (currentUser.isAdmin) {
                    reply("请选择重复对象哦|•ω•`)")
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            }) {
                childNode("group", {
                    if (currentUser.isAdmin) {
                        reply("请选择重复模式哦|•ω•`)")
                    } else {
                        reply("你的权限不足哦")
                        reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                    }
                }) {
                    childNode("on", {
                        val frequency = it.toDoubleOrNull() ?: 0.3
                        if (currentUser.isAdmin) {
                            if (!currentGroup.isRepeated) {
                                currentGroup.isRepeated = true
                                currentGroup.repeatFrequency = frequency
                                reply("本群的所有发言将被以${frequency}的概率重复，然而这真是无聊 |•ω•`)")
                                save()
                            } else {
                                currentGroup.repeatFrequency = frequency
                                reply("重复本群发言的概率被设置为$frequency |•ω•`)")
                                save()
                            }
                        } else {
                            reply("你的权限不足哦")
                            reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                        }
                    })
                    childNode("off", {
                        if (currentUser.isAdmin) {
                            if (!currentGroup.isRepeated) {
                                currentGroup.isRepeated = false
                                reply("本群已取消重复 |•ω•`)")
                                save()
                            } else {
                                reply("本来就没有在重复本群的发言啊 |•ω•`)")
                            }
                        } else {
                            reply("你的权限不足哦")
                            reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                        }
                    })
                }
                childNode("friend", {
                    if (currentUser.isAdmin) {
                        reply("请选择重复模式哦|•ω•`)")
                    } else {
                        reply("你的权限不足哦")
                        reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                    }
                }) {
                    childNode("on", {
                        var str = it.replace("  ", " ")
                        if (str.startsWith(" ")) str = str.substring(1)
                        val strs = str.split(" ")
                        if (strs.isEmpty()) {
                            reply("请输入被重复的用户的uin|•ω•`)")
                            null!!
                        }
                        val uin = strs[0].toLong()
                        val destUser = data._cylsFriendFromId[uin] ?: {
                            reply("未找到此人哦|•ω•`)")
                            null!!
                        }()
                        val frequency = strs[1].toDoubleOrNull() ?: 0.3
                        if (currentUser.isOwner || (currentUser.isAdmin && !destUser.isAdmin)) {
                            if (!destUser.isRepeated) {
                                destUser.isRepeated = true
                                destUser.repeatFrequency = frequency
                                reply("${getGroupUserNick(currentGroupId, uin)}的话将被以${frequency}的概率重复，然而这真是无聊 |•ω•`)")
                                save()
                            } else {
                                destUser.repeatFrequency = frequency
                                reply("重复${getGroupUserNick(currentGroupId, uin)}的话的概率被设置为$frequency |•ω•`)")
                                save()
                            }
                        } else {
                            reply("你的权限不足哦")
                            reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                        }
                    })
                    childNode("off", {
                        val uin = it.toLong()
                        val destUser = data._cylsFriendFromId[uin] ?: {
                            reply("未找到此人哦|•ω•`)")
                            null!!
                        }()
                        if (currentUser.isOwner || (currentUser.isAdmin && !destUser.isAdmin)) {
                            if (!destUser.isRepeated) {
                                reply("${getGroupUserNick(currentGroupId, uin)}并没有被重复啊 |•ω•`)")
                            } else {
                                destUser.isRepeated = false
                                reply("${getGroupUserNick(currentGroupId, uin)}已被取消重复 |•ω•`)")
                                save()
                            }
                        } else {
                            reply("你的权限不足哦")
                            reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                        }
                    })
                }
            }
            childNode("moha", {
                if (currentUser.isAdmin) {
                    reply("请选择被设置为moha的对象哦|•ω•`)")
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            }) {
                childNode("friend", {
                    if (currentUser.isAdmin) {
                        reply("请选择moha模式哦|•ω•`)")
                    } else {
                        reply("你的权限不足哦")
                        reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                    }
                }) {
                    childNode("on", {
                        val uin = it.toLong()
                        val destUser = data._cylsFriendFromId[uin] ?: {
                            reply("未找到此人哦|•ω•`)")
                            null!!
                        }()
                        if (currentUser.isOwner || (currentUser.isAdmin && !destUser.isAdmin)) {
                            if (!destUser.isMoha) {
                                destUser.isMoha = true
                                reply("${getGroupUserNick(currentGroupId, uin)}已被设置为moha专家，真是搞个大新闻 |•ω•`)")
                                save()
                            } else {
                                reply("${getGroupUserNick(currentGroupId, uin)}本来就是moha专家啊 |•ω•`)")
                                save()
                            }
                        } else {
                            reply("你的权限不足哦")
                            reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                        }
                    })
                    childNode("off", {
                        val uin = it.toLong()
                        val destUser = data._cylsFriendFromId[uin] ?: {
                            reply("未找到此人哦|•ω•`)")
                            null!!
                        }()
                        if (currentUser.isOwner || (currentUser.isAdmin && !destUser.isAdmin)) {
                            if (destUser.isMoha) {
                                destUser.isMoha = false
                                reply("${getGroupUserNick(currentGroupId, uin)}已被取消moha专家，一定是知识水平不够 |•ω•`)")
                                save()
                            } else {
                                reply("${getGroupUserNick(currentGroupId, uin)}本来就不是moha专家啊，为什么要搞大新闻 |•ω•`)")
                                save()
                            }
                        } else {
                            reply("你的权限不足哦")
                            reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                        }
                    })
                }
            }
            childNode("check", {
                reply("自检完毕\n一切正常哦|•ω•`)")
            })
            childNode("test", {
                if (currentUser.isOwner) {
                    reply("你是云裂的主人哦|•ω•`)")
                    reply("输入cyls.help.sudo查看……说好的主人呢，" +
                            "为什么连自己的权限都不知道(╯‵□′)╯︵┴─┴")
                } else if (currentUser.isAdmin) {
                    reply("你是云裂的管理员呢|•ω•`)")
                    reply("输入cyls.help.sudo来查看你的权限哦|•ω•`)")
                } else {
                    reply("你暂时只是个普通成员呢……|•ω•`)")
                    reply("输入cyls.help.sudo来查看你的权限哦|•ω•`)")
                }
            })
            childNode("say", {
                if (currentUser.isAdmin) {
                    reply(it)
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧，也可以让主人给你授权哦 |•ω•`)")
                }
            }) {
                childNode("friend", {
                    val index = it.indexOf(" ")
                    val userId = it.substring(0, index).toLong()
                    val content = it.substring(index + 1)
                    client.sendMessageToFriend(userId, content)
                })
                childNode("group", {
                    val index = it.indexOf(" ")
                    val groupId = it.substring(0, index).toLong()
                    val content = it.substring(index + 1)
                    client.sendMessageToGroup(groupId, content)
                })
            }
            childNode("save", {
                if (currentUser.isOwner) {
                    save()
                    reply("已保存完毕|•ω•`)")
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧 |•ω•`)")
                }
            })
            childNode("load", {
                if (currentUser.isOwner) {
                    load()
                    reply("已读取完毕|•ω•`)")
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧 |•ω•`)")
                }
            })
            childNode("quit", {
                if (currentUser.isOwner) {
                    reply("尝试关闭通讯中…… |•ω•`)")
                    reply("通讯已关闭，大家再……")
                    save()
                    System.exit(0)
                } else {
                    reply("你的权限不足哦")
                    reply("不如输入cyls.sudo.test查看你的权限吧 |•ω•`)")
                }
            })
        }
        childNode("util", {
            reply("""输入
cyls.help.util
查看帮助信息|•ω•`)""")
        }) {
            childNode("query", {
                reply("请选择查找的范围哦|•ω•`)")
            }) {
                childNode("groupuser", {
                    reply("开始查找|•ω•`)")
                    val groupUsers = getGroupInfoFromID(currentGroupId).users
                    groupUsers.forEach { user ->
                        val userName = getGroupUserNick(currentGroupId, user.uid)
                        if (userName.contains(it)) {
                            reply("${user.uid}:$userName")
                            sleep(100)
                        }
                    }
                })
                childNode("friend", {
                    reply("开始查找|•ω•`)")
                    data.cylsFriendList.forEach { friend ->
                        val friendInfo = friend.friend
                        if (friendInfo != null && friendInfo.nickname.contains(it)) {
                            reply("${friendInfo.userId}:${friendInfo.nickname}")
                        }
                    }
                })
                childNode("group", {
                    reply("开始查找|•ω•`)")
                    data.cylsGroupList.forEach { group ->
                        val groupInfo = group.group
                        if (groupInfo != null && groupInfo.name.contains(it)) {
                            reply("${groupInfo.id}:${groupInfo.name}")
                        }
                    }
                })
            }
            childNode("weather", {
                var str = it.replace("  ", " ")
                if (str.startsWith(" ")) str = str.substring(1)
                val strs = str.split(" ")
                if (strs.size >= 2) {
                    getWeather(strs[0], strs[1].toInt())
                } else reply("请输入城市名与天数|•ω•`)")
            }) {
                childNode("day0", {
                    var str = it.replace("  ", " ")
                    if (str.startsWith(" ")) str = str.substring(1)
                    val strs = str.split(" ")
                    if (strs.isNotEmpty()) {
                        getWeather(strs[0], 0)
                    } else reply("请输入城市名|•ω•`)")
                })
                childNode("day1", {
                    var str = it.replace("  ", " ")
                    if (str.startsWith(" ")) str = str.substring(1)
                    val strs = str.split(" ")
                    if (strs.isNotEmpty()) {
                        getWeather(strs[0], 1)
                    } else reply("请输入城市名|•ω•`)")
                })
                childNode("day2", {
                    var str = it.replace("  ", " ")
                    if (str.startsWith(" ")) str = str.substring(1)
                    val strs = str.split(" ")
                    if (strs.isNotEmpty()) {
                        getWeather(strs[0], 2)
                    } else reply("请输入城市名|•ω•`)")
                })
                childNode("today", {
                    var str = it.replace("  ", " ")
                    if (str.startsWith(" ")) str = str.substring(1)
                    val strs = str.split(" ")
                    if (strs.isNotEmpty()) {
                        getWeather(strs[0], 0)
                    } else reply("请输入城市名|•ω•`)")
                })
                childNode("tomorrow", {
                    var str = it.replace("  ", " ")
                    if (str.startsWith(" ")) str = str.substring(1)
                    val strs = str.split(" ")
                    if (strs.isNotEmpty()) {
                        getWeather(strs[0], 1)
                    } else reply("请输入城市名|•ω•`)")
                })
            }
            childNode("dice", {
                reply("人生有许多精彩，有些人却寄希望于这枚普通的六面体骰子|•ω•`)")
                sleep(200)
                reply("结果是：${randomInt(6) + 1}")
            })
            childNode("random", {
                val x = it.toIntOrNull()
                when (x) {
                    null                -> {
                        reply("还有这种骰子? |•ω•`)")
                        null!!
                    }
                    in Int.MIN_VALUE..1 -> {
                        reply("我这里可没有你要的面数的骰子|•ω•`)\n然而我可以现做一个")
                    }
                    2                   -> {
                        reply("这么重要的事情，你却抛硬币决定|•ω•`)")
                    }
                    6                   -> {
                        reply("人生有许多精彩，有些人却寄希望于这枚普通的六面体骰子|•ω•`)")
                    }
                    else                -> {
                        reply("有的人已经不满足于六面体骰子了，他们需要一个${x}面体的骰子|•ω•`)")
                    }
                }
                sleep(200)
                reply("结果是：${randomInt(x) + sign(x)}")
            })
            childNode("cal", {
                val expression = it.replace("&gt;", ">").replace("&lt;", "<")
                reply("结果是：${currentGroup.calculate(expression)}")
            })
        }
        childNode("help", {
            reply("""欢迎来到帮助系统|•ω•`)
cyls.help.sudo
查看关于云裂控制系统的帮助
cyls.help.util
查看云裂工具箱的帮助
更多功能等待你去发现哦|•ω•`)""")
        }) {
            childNode("sudo", {
                if (currentUser.isOwner) {
                    reply("""你可是云裂的主人呢，连这都不知道 |•ω•`)
可以让云裂屏蔽与解除屏蔽任何一名成员
cyls.sudo.ignore/recognize uid
可以将其他成员设置为云裂的管理员或取消管理员身份
cyls.sudo.authorize/unauthorize uid
可以进行通讯的中断与恢复
cyls.sudo.pause/resume
可以测试自己的权限
cyls.sudo.test
可以让云裂自检
cyls.sudo.check
可以让云裂说特定的内容
cyls.sudo.say 要说的话
还可以终止连接
cyls.sudo.quit
看你的权限这么多，你还全忘了 |•ω•`)""")
                } else if (currentUser.isAdmin) {
                    reply("""你是云裂的管理员，连这都不知道，一看就是新上任的|•ω•`)
可以让云裂屏蔽与解除屏蔽任何一名成员
cyls.sudo.ignore/recognize uid
可以进行通讯的中断与恢复
cyls.sudo.pause/resume
可以测试自己的权限
cyls.sudo.test
可以让云裂自检
cyls.sudo.check
可以让云裂说特定的内容
cyls.sudo.say 要说的话""")
                } else {
                    reply("""你是普通成员，权限有限呢|•ω•`)
可以测试自己的权限
cyls.sudo.test
可以让云裂自检
cyls.sudo.check
不如向主人申请权限吧|•ω•`)""")
                }
            })
            childNode("util", {
                reply("""目前云裂的工具功能还不怎么完善呢|•ω•`)
你可以查询群成员：
cyls.util.query.groupuser [群名片的一部分]
查询云裂的好友：
cyls.util.query.friend [昵称的一部分]
查询群：
cyls.util.query.group [群名的一部分]
你可以查询天气：
cyls.util.weather
可以掷骰子或计算1至[最大值]的随机数:
cyls.util.dice
cyls.util.random [最大值]
可以进行简单的计算：
cyls.util.cal [表达式/代码块]
关于天气功能的更多内容，输入
cyls.help.util.weather
来查看哦|•ω•`)""")
            }) {
                childNode("weather", {
                    reply("""
云裂的天气查询功能目前只能查到近几日的天气|•ω•`)
[天数]: 0 -> 今天, 1 -> 明天, 2 -> 后天
cyls.util.weather [城市名] [天数]
cyls.util.weather.today [城市名]
cyls.util.weather.tomorrow [城市名]
cyls.util.weather.day[天数] [城市名]
例如：
cyls.util.weather.day2 无锡
查询无锡后天的天气。
""")
                })
            }
        }
    }

    private val groupMainVerifier = createVerifier {
        regex("(\\.|…|。|\\[\"face\",\\d+])*晚安(\\.|…|。|\\[\"face\",\\d+])*") {
            val hasGreeted = currentGroup.hasGreeted
            currentGroup.addGreeting()
            if (!hasGreeted) reply("晚安，好梦|•ω•`)")
        }
        regex("(\\.|…|。|\\[\"face\",\\d+])*早安?(\\.|…|。|\\[\"face\",\\d+])*") {
            val hasGreeted = currentGroup.hasGreeted
            currentGroup.addGreeting()
            if (!hasGreeted) reply("早|•ω•`)")
        }
        anyOf({
            contain("有没有")
            containRegex("有.{0,5}((?<!什)么|吗)")
        }) {
            reply(itemByChance(
                    "没有（逃|•ω•`)",
                    "有（逃|•ω•`)"
            ))
        }
        anyOf({
            contain("是不是")
            containRegex("是.{0,5}((?<!什)么|吗)")
        }) {
            reply(itemByChance(
                    "不是（逃|•ω•`)",
                    "是（逃|•ω•`)"
            ))
        }
        anyOf({
            contain("会不会")
            containRegex("会.{0,5}((?<!什)么|吗)")
        }) {
            reply(itemByChance(
                    "不会（逃|•ω•`)",
                    "会（逃|•ω•`)"
            ))
        }
        anyOf({
            contain("喜不喜欢")
            containRegex("喜欢.{0,5}((?<!什)么|吗)")
        }) {
            reply(itemByChance(
                    "喜欢（逃|•ω•`)",
                    "不喜欢（逃|•ω•`)"
            ))
        }
        containPath("云裂") {
            contain("自检") {
                reply("自检完毕\n一切正常哦|•ω•`)")
            }
            default {
                val result = ToAnalysis.parse(it)
                if (result.filter { it.realName == "云裂" || it.realName == "穿云裂石" }.isNotEmpty())
                    reply("叫我做什么|•ω•`)")
            }
        }
        contain("表白", {
            val result = ToAnalysis.parse(it)
            if (it.matches(".*表白云裂.*".toRegex()))
                reply("表白${getGroupUserNick(currentGroupMessage.groupId, currentGroupMessage.userId)}|•ω•`)")
            else if (result.filter { it.realName == "表白" }.isNotEmpty()) reply("表白+1 |•ω•`)")
        })
        anyOf({
            contain("什么操作")
            contain("这种操作")
            contain("新的操作")
        }) {
            reply("一直都有这种操作啊|•ω•`)")
        }
        containRegex("你们?(再?一直再?|再?继续再?)?这样(下去)?是不行的", {
            reply("再这样的话是不行的|•ω•`)")
        })
        anyOf({
            containRegex("原因么?要?(自己)?找一?找")
            contain("什么原因")
            contain("引起重视")
            contain("知名度")
        }) {
            reply(itemByChance(
                    "什么原因么自己找一找|•ω•`)",
                    "这个么要引起重视|•ω•`)",
                    "lw:我的知名度很高了，不用你们宣传了|•ω•`)"
            ))
        }
        special {
            if (!currentUser.isMoha)
                commonMohaVerifier.findAndRun(it)
            else
                mohaExpertVerifier.findAndRun(it)
        }
    }

    private val commonMohaVerifier = createVerifier {
        anyOf({
            contain("大新闻")
            contain("知识水平")
            contain("谈笑风生")
            contain("太暴力了")
            contain("这样暴力")
            contain("暴力膜")
            contain("江来")
            contain("泽任")
            contain("民白")
            contain("批判一番")
            contain("真正的粉丝")
            contain("江信江疑")
            contain("听风就是雨")
            contain("长者")
        }) {
            reply(itemByChance(
                    "不要整天搞个大新闻|•ω•`)",
                    "你们还是要提高自己的知识水平|•ω•`)",
                    "你们这样是要被拉出去续的|•ω•`)",
                    "真正的粉丝……|•ω•`)"
            ))
        }
        anyOf({
            containRegex("高[到了]不知道?(那里去|多少)")
            containRegex("报道上?([江将]来)?(要是)?出了偏差")
            containRegex("(我(今天)?)?(算是)?得罪了?你们?一下")
        }) {
            reply(itemByChance(
                    "迪兰特比你们不知道高到哪里去了，我和他谈笑风生|•ω•`)",
                    "江来报道上出了偏差，你们是要负泽任的，民不民白?|•ω•`)",
                    "我今天算是得罪了你们一下|•ω•`)"
            ))
        }
        equal("续") {
            reply("吃枣药丸|•ω•`)")
        }
        regex("苟(\\.|…|。|\\[\"face\",\\d+])*") {
            reply("富贵，无相忘|•ω•`)")
        }
    }

    private val mohaExpertVerifier = createVerifier {
        anyOf({
            contain("大新闻")
            contain("知识水平")
            contain("谈笑风生")
            contain("太暴力了")
            contain("这样暴力")
            contain("暴力膜")
            contain("江")
            contain("泽任")
            contain("民白")
            contain("批判一番")
            contain("知识水平")
            contain("angry")
            contain("moha")
            contain("真正的粉丝")
            contain("听风就是雨")
            contain("长者")
            contain("苟")
            contain("哪里去")
            contain("他")
            contain("得罪")
            contain("报道")
            contain("偏差")
        }) {
            reply(itemByChance(
                    "不要整天搞个大新闻|•ω•`)",
                    "你们还是要提高自己的知识水平|•ω•`)",
                    "你们这样是要被拉出去续的|•ω•`)",
                    "真正的粉丝……|•ω•`)",
                    "迪兰特比你们不知道高到哪里去了，我和他谈笑风生|•ω•`)",
                    "江来报道上出了偏差，你们是要负泽任的，民不民白?|•ω•`)",
                    "我今天算是得罪了你们一下|•ω•`)",
                    "吃枣药丸|•ω•`)"
            ))
        }
        contain("苟") {
            reply("富贵，无相忘|•ω•`)")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        ToAnalysis.parse("233").toString() //初始化分词库，无实际作用

        client = SmartQQClient(callback, qrCodeFile)
        client.start()
        load()

        Thread {
            while (true) {
                val timeTillMorning = timeFrom(timeOf(tomorrowName + " 06:00:00", TimeFormat.FULL), Date())
                sleep(timeTillMorning)
                data.cylsFriendList.forEach {
                    val friend = it.friend
                    if (friend != null) {
                        client.sendMessageToFriend(friend.userId, "早安|•ω•`)")
                    }
                }
            }
        }.start()

        //为防止请求过多导致服务器启动自我保护
        //群id到群详情映射 将在第一次请求时创建
    }
}