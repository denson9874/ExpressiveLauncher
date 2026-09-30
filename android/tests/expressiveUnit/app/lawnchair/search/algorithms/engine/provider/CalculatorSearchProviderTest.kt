package app.lawnchair.search.algorithms.engine.provider

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The calculator row is on by default and becomes the Enter target when no app matches, so it
 * must only answer queries that actually calculate something.
 */
@RunWith(Parameterized::class)
class CalculatorSearchProviderTest(
    private val query: String,
    private val expectedResult: String?,
) {

    @Test
    fun calculatorOnlyAnswersRealCalculations() {
        val calculation = CalculatorSearchProvider.calculateEquationFromString(query)

        if (expectedResult == null) {
            assertWithMessage("calculator row for '$query' showed '${calculation.result}'")
                .that(calculation.isValid)
                .isFalse()
        } else {
            assertThat(calculation.isValid).isTrue()
            assertThat(calculation.result).isEqualTo(expectedResult)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun cases(): List<Array<Any?>> = listOf(
            // Real calculations: a number plus an operator or a function call.
            arrayOf("12*7", "84"),
            arrayOf("2+2", "4"),
            arrayOf("sqrt(16)", "4"),
            arrayOf("3^2", "9"),
            arrayOf("10/4", "2.5"),
            arrayOf("-5+2", "-3"),
            arrayOf("7-11", "-4"),
            arrayOf("10%4", "2"),
            arrayOf("abs(-5)", "5"),
            arrayOf("√16", "4"),
            arrayOf("2*pi", "6.283185307179586"),
            // Bare numbers are not calculations.
            arrayOf("2024", null),
            arrayOf("-5", null),
            arrayOf("3.14", null),
            arrayOf("-2.5", null),
            arrayOf("1e5", null),
            arrayOf("(42)", null),
            // Bare constants, or constants without any number, are not calculations.
            arrayOf("e", null),
            arrayOf("pi", null),
            arrayOf("PI", null),
            arrayOf("pi*e", null),
        )
    }
}
