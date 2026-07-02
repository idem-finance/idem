package finance.idem

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<IdemApplication>().with(TestcontainersConfiguration::class).run(*args)
}
