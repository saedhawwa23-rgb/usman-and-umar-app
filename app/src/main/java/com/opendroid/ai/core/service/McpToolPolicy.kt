package com.opendroid.ai.core.service

/**
 * Safe tool policy for a local MCP transport with no per-request UI approval channel.
 * It intentionally contains no adapter to app actions, shell commands, or endpoints.
 */
object McpToolPolicy {
    private const val MAX_EXPRESSION_LENGTH = 256

    fun availableActionIds(): List<String> = listOf("CALCULATE")

    fun isActionAllowed(actionId: String): Boolean = actionId.equals("CALCULATE", ignoreCase = true)

    fun calculate(expression: String): Double {
        require(expression.isNotBlank()) { "expression is required" }
        require(expression.length <= MAX_EXPRESSION_LENGTH) { "expression is too long" }
        val parser = ArithmeticParser(expression)
        val result = parser.parse()
        require(result.isFinite()) { "calculation result is not finite" }
        return result
    }

    private class ArithmeticParser(private val source: String) {
        private var index = 0

        fun parse(): Double {
            val value = expression()
            whitespace()
            require(index == source.length) { "unsupported token at position $index" }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                whitespace()
                value = when {
                    consume('+') -> value + term()
                    consume('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                whitespace()
                value = when {
                    consume('*') -> value * factor()
                    consume('/') -> {
                        val divisor = factor()
                        require(divisor != 0.0) { "division by zero" }
                        value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun factor(): Double {
            whitespace()
            if (consume('+')) return factor()
            if (consume('-')) return -factor()
            if (consume('(')) {
                val value = expression()
                whitespace()
                require(consume(')')) { "missing closing parenthesis" }
                return value
            }
            return number()
        }

        private fun number(): Double {
            whitespace()
            val start = index
            var decimalPointSeen = false
            while (index < source.length) {
                val char = source[index]
                when {
                    char.isDigit() -> index++
                    char == '.' && !decimalPointSeen -> {
                        decimalPointSeen = true
                        index++
                    }
                    else -> break
                }
            }
            require(index > start && source.substring(start, index) != ".") { "number expected at position $index" }
            return source.substring(start, index).toDouble()
        }

        private fun consume(char: Char): Boolean {
            if (index < source.length && source[index] == char) {
                index++
                return true
            }
            return false
        }

        private fun whitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }
}
