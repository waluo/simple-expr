package org.waluo

import org.jparsec.*
import java.util.*
import java.util.function.BiFunction

/**
 * waluo
 * 2019-05-10.
 */

object ExprGrammar {
    private val OPERATORS = arrayOf("=", ">", "<", ">=", "<=", "!=", ".", ",", "(", ")")

    private val KEYWORDS = arrayOf("and", "or", "in", "not", "true", "TRUE", "false", "FALSE")

    private val TERMS = Terminals.operators(*OPERATORS)
            .words(Scanners.IDENTIFIER)
            .caseInsensitiveKeywords(listOf(*KEYWORDS)).build()

    private val TOKENIZER = Parsers.or(
            Terminals.DecimalLiteral.TOKENIZER,
            Terminals.StringLiteral.SINGLE_QUOTE_TOKENIZER,
            TERMS.tokenizer())

    @JvmStatic
    fun parser(source: String): Expression {

        operator fun LogicalExpression.plus(right: Expression): LogicalExpression =
                when {
                    right is LogicalExpression && right.op == op -> LogicalExpression(op, list + right.list)
                    right is LogicalExpression && right.op != op -> LogicalExpression(op, listOf(this, right))
                    else -> LogicalExpression(op, list + right)
                }

        val str = Terminals.StringLiteral.PARSER.map { StrValue(it) }
        val num = Terminals.DecimalLiteral.PARSER.map { NumValue(it.toBigDecimal()) }
        val variable = Terminals.Identifier.PARSER.label("variable")
        val operand = Parsers.or(num, str).label("operand")

        fun term(vararg term: String): Parser<*> {
            return TERMS.token(*term)
        }

        fun <T> termIn(operand: Parser<T>): Parser<List<T>> {
            return term("in").next(operand.sepBy(term(",")).between(term("("), term(")")))
        }

        fun binary(op: Parser<Op>): Parser<BinaryExpression> {
            return Parsers.sequence(variable, op, operand) { l, o, r ->
                when (r) {
                    is StrValue -> BinaryExpression(l, o, r.str)
                    is NumValue -> BinaryExpression(l, o, r.num)
                }
            }
        }

        fun not(exp: Parser<Expression>): Parser<NotExpression> {
            return Parsers.sequence(term("not"), exp).map { NotExpression(it) }
        }

        val binary = Parsers.or(
                Parsers.sequence(variable, termIn(str)) { left, right ->
                    InExpression(left, right.map { it.str })
                },
                Parsers.sequence(variable, termIn(num)) { left, right ->
                    InExpression(left, right.map { it.num })
                },
                binary(term("=").retn(Op.EQ)),
                binary(term(">").retn(Op.GT)),
                binary(term("<").retn(Op.LT)),
                binary(term(">=").retn(Op.GTE)),
                binary(term("<=").retn(Op.LTE)),
                binary(term("!=").retn(Op.NE)),
                term("true", "TRUE").retn(TRUE),
                term("false", "FALSE").retn(FALSE))

        val notBinary = not(binary)

        fun logical(expr: Parser<Expression>): Parser<Expression> {
            fun logical(op: Op): BiFunction<Expression, Expression, LogicalExpression> {
                return BiFunction { left, right ->
                    when {
                        left is LogicalExpression && left.op == op -> left + right
                        right is LogicalExpression && op == right.op -> right + left
                        else -> LogicalExpression(op, listOf(left, right))
                    }
                }
            }

            val ref = Parser.newReference<Expression>()
            val logical = OperatorTable<Expression>()
                    .infixl(term("and").retn(logical(Op.AND)), 10)
                    .infixl(term("or").retn(logical(Op.OR)), 10)
                    .build(ref.lazy().between(term("("), term(")")).or(expr))
                    .label("logical expression")
            ref.set(logical)
            return logical
        }

        val logical = logical(binary.or(notBinary))

        val notLogical = not(logical)

        return logical.or(notLogical)
                .from(TOKENIZER, Scanners.WHITESPACES.skipMany())
                .parse(source)
    }

}