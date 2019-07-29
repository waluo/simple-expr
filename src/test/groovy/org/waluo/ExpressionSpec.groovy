package org.waluo

import spock.lang.Specification

class ExpressionSpec extends Specification {

    def 'simple expr'() {
        when:
        def parser = ExprGrammar.parser(expStr)
        def matchT = ExpressionKt.execute(parser, caseT)
        def matchF = ExpressionKt.execute(parser, caseF)

        then:
        println("${msg} : ${expStr}")
        matchT
        !matchF

        where:
        expStr                  | caseT                | caseF                | msg
        "a = '010'"             | [a: "010", b: "???"] | [a: "110", b: "???"] | "base ="
        "a != 10"               | [a: "9"]             | [a: "10"]            | "base !="
        "a > 10"                | [a: "11"]            | [a: "-10"]           | "base >"
        "a >= 10"               | [a: "10"]            | [a: "9"]             | "base >="
        "a < 10"                | [a: "9"]             | [a: "10"]            | "base <"
        "a <= 10"               | [a: "10"]            | [a: "11"]            | "base <="
        "a in (18,19,20)"       | [a: "19"]            | [a: "22"]            | "base in str"
        "a in ('18','19','20')" | [a: "19"]            | [a: "22"]            | "base in num"
        "not a = '010'"         | [a: "011"]           | [a: "010"]           | "base not"
        "a > '22'"              | [a: "3"]             | [a: "111"]           | "string order"
        "a > 1 and b > 2"       | [a: "2", b: "3"]     | [a: "2"]             | "logical and"
        "a > 1 or b > 2"        | [b: "3"]             | [a: "1"]             | "logical or"
        "not(a = 1 or b = 2)"   | [a: "2"]             | [b: "2"]             | "logical not"
    }

    def 'variables'() {
        expect:
        ExpressionKt.getVariables(ExprGrammar.parser(expStr)) == variables

        where:
        expStr                | variables
        "a > 3 and a < 9"     | ["a"]
        "city in ('beijing')" | ["city"]
        "not(a = 1 or b = 2)" | ["a", "b"]
    }

    def 'json'() {
        expect:
        def exp = ExprGrammar.parser("a > 3 and a < 9")
        exp == SerializationKt.toExpression(SerializationKt.toJson(exp))
    }
}
