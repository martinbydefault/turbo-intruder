import java.net.InetAddress
import java.net.URL
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread


fun main(args : Array<String>) {
    val url = URL(args[0])
    val requests = args[1].toInt();
    val threads = args[2].toInt()
    val readFreq = args[3].toInt();
    val requestsPerThread = requests / threads;

    val request = ("GET ${url.path} HTTP/1.1\r\n"
                  +"Host: ${url.host}\r\n"
                  +"Connection: keep-alive\r\n"
                  +"\r\n").toByteArray(Charsets.ISO_8859_1)

    val ipAddress = InetAddress.getByName(url.host)
    val port = if (url.port == -1) { url.defaultPort } else { url.port }

    val trustingSslContext = SSLContext.getInstance("TLS")
    trustingSslContext.init(null, arrayOf<TrustManager>(TrustingTrustManager()), null)
    val trustingSslSocketFactory = trustingSslContext.socketFactory

    val start = System.nanoTime()
    val latch = CountDownLatch(threads)
    var totalBytes = 0
    val statusMap = HashMap<Int,Int>()

    for(j in 1..threads) {
        thread {
            sendRequests(url, trustingSslSocketFactory, ipAddress, port, requestsPerThread, request, statusMap, totalBytes, latch, readFreq)
        }
    }
    latch.await()

    for((status, freq) in statusMap) {
        println("Status ${status} count ${freq}")
    }

    println("Bytes read: ${totalBytes}")

    val time = System.nanoTime() - start
    println("Time: " + "%.2f".format(time.toFloat() / 1000000000))
}

private fun sendRequests(url: URL, trustingSslSocketFactory: SSLSocketFactory, ipAddress: InetAddress?, port: Int, requestsPerThread: Int, request: ByteArray, statusMap: HashMap<Int, Int>, totalBytes: Int, latch: CountDownLatch, readFreq: Int) {
    var totalBytes1 = totalBytes
    var threadBytes = 0
    try {
        val socket = if (url.protocol.equals("https")) {
            trustingSslSocketFactory.createSocket(ipAddress, port)
        } else {
            SocketFactory.getDefault().createSocket(ipAddress, port)
        }

        for (loop in 1..requestsPerThread/readFreq) {

            

            for (i in 1..readFreq) {
                socket.getOutputStream().write(request)
            }
            val read = ByteArray(1024)
            var buffer = ""

            for (i in 1..readFreq) {
                var delimOffset = buffer.indexOf("\r\n\r\n")
                while (delimOffset == -1) {
                    var len = socket.getInputStream().read(read)

                    buffer += String(read.copyOfRange(0, len), Charsets.ISO_8859_1)
                    delimOffset = buffer.indexOf("\r\n\r\n")
                }

                val contentLength = Regex("Content-Length: (\\d+)").find(buffer)!!.groups[1]!!.value.toInt()
                val responseLength = delimOffset + contentLength + 4

                while (buffer.length < responseLength) {
                    val len = socket.getInputStream().read(read)
                    buffer += String(read.copyOfRange(0, len), Charsets.ISO_8859_1)
                }

                val msg = buffer.substring(0, responseLength)
                buffer = buffer.substring(responseLength)
                threadBytes += responseLength

                if (!msg.startsWith("HTTP")) {
                    println("Error")
                    break
                }
                val status = msg.split(" ")[1].toInt()
                synchronized(statusMap) {
                    statusMap.put(status, statusMap.getOrDefault(status, 0) + 1)
                }
            }
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
    } finally {
        synchronized(totalBytes1) {
            totalBytes1 += threadBytes
        }
        latch.countDown()
    }
}


private class TrustingTrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate>? {
        return null
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
}