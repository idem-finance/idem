package finance.idem

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class IdemApplication

fun main(args: Array<String>) {
    runApplication<IdemApplication>(*args)
}
