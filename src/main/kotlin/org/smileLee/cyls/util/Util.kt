package org.smileLee.cyls.util

import java.text.SimpleDateFormat
import java.util.*

object Util {
    enum class TimeFormat(val format: String) {
        FULL("yyyy-MM-dd hh:mm:ss"),
        DATE("yyyy-MM-dd"),
        TIME("hh:mm:ss"),
        MINUTE("hh:mm"),
    }

    /**
     * 获取本地系统时间

     * @return 本地系统时间
     */
    fun getTimeName(format: String, time: Date = Date()): String = SimpleDateFormat(format).format(time)

    @JvmOverloads
    fun getTimeName(format: TimeFormat, time: Date = Date()) = getTimeName(format.format, time)

    val fullDay = (24 * 60 * 60 * 1000).toLong()

    val timeName get() = getTimeName(TimeFormat.FULL)
    val todayName get() = getTimeName(TimeFormat.DATE)
    val tomorrowName get() = getTimeName(TimeFormat.DATE, Date(Date().time + fullDay))

    fun timeOf(name: String, format: String): Date = SimpleDateFormat(format).parse(name)
    fun timeOf(name: String, format: TimeFormat) = timeOf(name, format.format)
    fun timeFrom(date: Date, former: Date) = date.time - former.time

    class Order(val path: ArrayList<String>, val message: String)

    private fun String.indexOfOrLength(vararg char: Char): Int {
        var ret = length
        fun check(x: Int) = if (x != -1) x else length
        char.forEach {
            ret = minOf(ret, check(indexOf(it)))
        }
        return ret
    }

    /**
     * 将指令转为路径
     */
    fun readOrder(string: String): Order {
        var str = string
        val path = ArrayList<String>()
        while (true) {
            val dotIndex = str.indexOfOrLength('.')
            val blankIndex = str.indexOfOrLength(' ', '\n')
            str = when {
                blankIndex == dotIndex -> {
                    path.add(str)
                    return Order(path, "")
                }
                blankIndex > dotIndex  -> {
                    path.add(str.substring(0, dotIndex))
                    str.substring(dotIndex + 1)
                }
                else                   -> {
                    path.add(str.substring(0, blankIndex))
                    return Order(path, str.substring(blankIndex + 1))
                }
            }
        }
    }

    fun randomInt(x: Int = 2) = (Math.random() * x).toInt()
    val randomBool get() = Math.random() >= 0.5
    fun sign(x: Int) = if (x > 0) 1 else if (x < 0) -1 else 0

    inline fun runByChance(chance: Double, action: () -> Unit) {
        if (Math.random() < chance) action()
    }

    inline fun <T> runByChance(chance: Double, a: () -> T, b: () -> T)
            = if (Math.random() < chance) a() else b()

    fun <T> runByChance(vararg actions: () -> T): T {
        val index = randomInt(actions.size)
        return actions[index]()
    }

    fun <T> itemByChance(vararg items: T): T {
        val index = randomInt(items.size)
        return items[index]
    }

    inline fun doWithLog(message: String, action: () -> Unit) {
        println("[$timeName] 开始$message...")
        action()
        println("[$timeName] ${message}成功。")
    }
}