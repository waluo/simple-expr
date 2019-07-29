package org.waluo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.math.BigDecimal

/**
 * waluo
 * 2019-05-10.
 */

val mapper = jacksonObjectMapper()

private data class FlatExpression(
        val variable: String? = null,
        val op: Op? = null,
        val operandStr: String? = null,
        val operandNum: BigDecimal? = null,
        val listStr: List<String>? = null,
        val listNum: List<BigDecimal>? = null,
        val logical: List<FlatExpression>? = null,
        val right: FlatExpression? = null
)

private fun FlatExpression.toStruct(): Expression {
    return when (this.op) {
        null -> TRUE
        Op.NOT -> NotExpression(this.right!!.toStruct())
        Op.AND, Op.OR -> LogicalExpression(this.op, this.logical!!.map { it.toStruct() })
        Op.IN -> InExpression(this.variable!!, this.listStr ?: this.listNum!!)
        else -> BinaryExpression(this.variable!!, this.op, this.operandStr ?: this.operandNum!!)
    }
}

private fun Expression.toFlat(): FlatExpression {
    fun InExpression.isNum(): Boolean {
        return list.all {
            when (it) {
                is BigDecimal -> true
                else -> false
            }
        }
    }

    fun BinaryExpression.isNum(): Boolean {
        return when (operand) {
            is BigDecimal -> true
            else -> false
        }
    }
    return when (this) {
        is InExpression -> FlatExpression(
                op = Op.IN,
                variable = this.variable,
                listStr = if (!this.isNum()) this.list.map { it.toString() } else null,
                listNum = if (this.isNum()) this.list.map { it as BigDecimal } else null
        )
        is BinaryExpression -> FlatExpression(
                op = this.op,
                variable = this.variable,
                operandStr = if (!this.isNum()) this.operand.toString() else null,
                operandNum = if (this.isNum()) this.operand as BigDecimal else null
        )
        is LogicalExpression -> FlatExpression(
                op = this.op,
                logical = this.list.map { it.toFlat() }
        )
        is NotExpression -> FlatExpression(
                op = Op.NOT,
                right = this.right.toFlat()
        )
        TRUE -> FlatExpression()
        FALSE -> FlatExpression(
                op = Op.NOT,
                right = FlatExpression()
        )
    }
}

fun Expression.toJson(): String {
    return mapper.writeValueAsString(this.toFlat())
}

fun String.toExpression(): Expression {
    return mapper.readValue<FlatExpression>(this).toStruct()
}