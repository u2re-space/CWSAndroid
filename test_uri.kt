import java.net.URI

fun main() {
    println(URI("http://:192.168.0.110:8080/clipboard").toString())
    println(URI("http://id:192.168.0.110:8080/clipboard").toString())
}
