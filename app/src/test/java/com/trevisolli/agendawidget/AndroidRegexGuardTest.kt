package com.trevisolli.agendawidget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O regex do Android é o ICU, não o do Java: flags embutidas como (?U) ou UNICODE_CHARACTER_CLASS
 * passam nos testes de JVM mas derrubam o app no celular (foi o bug da v3.7). Este teste barra o uso.
 */
class AndroidRegexGuardTest {
    @Test fun semRecursosDeRegexQueOAndroidNaoTem() {
        val forbidden = listOf("(?U)", "UNICODE_CHARACTER_CLASS", "(?U:")
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (forbidden.any { it in code }) "${f.name}:${i + 1}" else null
            } }
            .toList()
        assertTrue("Regex não suportado no Android em: $offenders", offenders.isEmpty())
    }
}
