package org.waluo

import java.math.BigDecimal


/**
 * waluo
 * 2018/8/30.
 */
sealed class Value

data class NumValue(val num: BigDecimal) : Value()
data class StrValue(val str: String) : Value()

sealed class Expression
object TRUE : Expression()
object FALSE : Expression()
data class InExpression(val variable: String, val list: List<Any>) : Expression()
data class BinaryExpression(val variable: String, val op: Op, val operand: Any) : Expression()
data class LogicalExpression(val op: Op, val list: List<Expression>) : Expression()
data class NotExpression(val right: Expression) : Expression()

enum class Op {
    IN, EQ, GT, LT, GTE, LTE, NE, AND, OR, NOT;
}

val Expression.variables: List<String>
    get() =
        when {
            this is InExpression -> listOf(this.variable)
            this is BinaryExpression -> listOf(this.variable)
            this is LogicalExpression -> this.list.flatMap { it.variables }
            this is NotExpression -> this.right.variables
            else -> listOf()
        }.distinct()

fun Expression.execute(args: Map<String, String>): Boolean {
    operator fun BinaryExpression.compareTo(value: String): Int =
            if (operand is BigDecimal) {
                value.toBigDecimal().compareTo(operand)
            } else {
                value.compareTo(operand.toString())
            }

    return when (this) {
        is InExpression -> this.list.any {
            if (it is BigDecimal) {
                args[this.variable]?.toBigDecimal() == it
            } else {
                args[this.variable] == it
            }
        }
        is BinaryExpression -> {
            val arg = args[this.variable]
            if (arg != null)
                when (this.op) {
                    Op.EQ -> this.compareTo(arg) == 0
                    Op.NE -> this.compareTo(arg) != 0
                    Op.GT -> this > arg
                    Op.LT -> this < arg
                    Op.GTE -> this >= arg
                    Op.LTE -> this <= arg
                    else -> false
                }
            else false
        }
        is LogicalExpression -> if (this.op == Op.AND)
            this.list.all { it.execute(args) }
        else
            this.list.any { it.execute(args) }
        is NotExpression -> this.right.execute(args).not()
        TRUE -> true
        FALSE -> false
    }
}