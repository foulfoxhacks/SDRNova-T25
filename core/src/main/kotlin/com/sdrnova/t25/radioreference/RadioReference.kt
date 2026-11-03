package com.sdrnova.t25.radioreference

import org.w3c.dom.Element
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** A RadioReference Premium Subscriber's login, plus the application key RadioReference issues to each app. */
data class RrAuth(val username: String, val password: String, val appKey: String)

class RadioReferenceException(message: String, val code: String? = null) : IOException(message)

data class RrZip(val zip: Int, val city: String, val countyId: Int, val stateId: Int)

data class RrCounty(val id: Int, val name: String, val systems: List<RrSystemRef>, val categories: List<RrCategory>)

data class RrSystemRef(val id: Int, val name: String, val type: Int, val city: String)

data class RrCategory(val id: Int, val name: String, val subcategories: List<RrSubcategory>)

data class RrSubcategory(val id: Int, val name: String)

data class RrSite(
    val id: Int,
    val number: Int,
    val description: String,
    val rfss: Int,
    val nac: String,
    val countyId: Int,
    val countyName: String,
    /** Free text from the database, such as "LSM" or "C4FM". */
    val modulation: String,
    val frequencies: List<RrSiteFrequency>,
)

data class RrSiteFrequency(val lcn: Int, val mhz: BigDecimal, val use: String) {
    /** RadioReference marks control channels "d" (primary) and "a" (alternate). */
    val control: Boolean get() = use.contains('d', ignoreCase = true) || use.contains('c', ignoreCase = true)
    val alternate: Boolean get() = use.contains('a', ignoreCase = true)
}

data class RrTalkgroup(
    val decimal: Int,
    val alpha: String,
    val description: String,
    val mode: String,
    val encrypted: Boolean,
    val categoryId: Int,
)

data class RrTalkgroupCategory(val id: Int, val name: String)

data class RrFrequency(
    val mhz: BigDecimal,
    val alpha: String,
    val description: String,
    /** A mode ID or name; [RadioReferenceClient.modeName] turns IDs into names like "FMN" or "P25". */
    val mode: String,
    val tone: String,
    val encrypted: Boolean,
)

/**
 * RadioReference's SOAP database service (https://api.radioreference.com/soap2): zip code to county, the county's
 * trunked systems and conventional categories, and each system's sites and talkgroups. Calls block; run them off the
 * main thread. [transport] posts a SOAP envelope for an action and returns the response body.
 */
class RadioReferenceClient(
    private val auth: RrAuth,
    private val transport: (action: String, envelope: String) -> String = ::httpPost,
) {
    private val modeNames = HashMap<Int, String>()
    private val typeNames = HashMap<Int, String>()

    fun zipcode(zip: Int): RrZip {
        val result = call("getZipcodeInfo", "zipcode" to zip.toString())
        return RrZip(zip, result.text("city"), result.int("ctid"), result.int("stid"))
    }

    fun county(id: Int): RrCounty {
        val result = call("getCountyInfo", "ctid" to id.toString())
        val systems = result.child("trsList")?.items().orEmpty().map {
            RrSystemRef(it.int("sid"), it.text("sName"), it.int("sType"), it.text("sCity"))
        }
        val categories = result.child("cats")?.items().orEmpty().map { cat ->
            RrCategory(
                cat.int("cid"), cat.text("cName"),
                cat.child("subcats")?.items().orEmpty().map { RrSubcategory(it.int("scid"), it.text("scName")) },
            )
        }
        return RrCounty(result.int("ctid").takeIf { it != 0 } ?: id, result.text("countyName"), systems, categories)
    }

    /** "Project 25 Phase II", "Motorola Type II Smartzone", "DMR Tier III" and so on. */
    fun systemTypeName(type: Int): String = typeNames.getOrPut(type) {
        val result = call("getTrsType", "id" to type.toString())
        result.items().firstOrNull()?.text("sTypeDescr") ?: result.text("sTypeDescr")
    }

    fun sites(systemId: Int): List<RrSite> = call("getTrsSites", "sid" to systemId.toString()).items().map { site ->
        RrSite(
            id = site.int("siteId"),
            number = site.int("siteNumber"),
            description = site.text("siteDescr"),
            rfss = site.int("rfss"),
            nac = site.text("nac"),
            countyId = site.int("siteCtid"),
            countyName = site.text("siteCt"),
            modulation = site.text("siteModulation"),
            frequencies = site.child("siteFreqs")?.items().orEmpty().mapNotNull { f ->
                f.decimal("freq")?.let { RrSiteFrequency(f.int("lcn"), it, f.text("use")) }
            },
        )
    }

    fun talkgroups(systemId: Int): List<RrTalkgroup> =
        call("getTrsTalkgroups", "sid" to systemId.toString(), "tgCid" to "0", "tgTag" to "0", "tgDec" to "0").items().map { tg ->
            val mode = tg.text("tgMode")
            RrTalkgroup(
                decimal = tg.int("tgDec"),
                alpha = tg.text("tgAlpha"),
                description = tg.text("tgDescr"),
                mode = mode,
                encrypted = tg.int("enc") > 0 || mode.contains('E', ignoreCase = true),
                categoryId = tg.int("tgCid"),
            )
        }

    fun talkgroupCategories(systemId: Int): List<RrTalkgroupCategory> =
        call("getTrsTalkgroupCats", "sid" to systemId.toString()).items().map { RrTalkgroupCategory(it.int("tgCid"), it.text("tgCname")) }

    fun subcategoryFrequencies(subcategoryId: Int): List<RrFrequency> =
        call("getSubcatFreqs", "scid" to subcategoryId.toString()).items().mapNotNull { f ->
            f.decimal("out")?.let {
                RrFrequency(it, f.text("alpha"), f.text("descr"), f.text("mode"), f.text("tone"), f.int("enc") > 0)
            }
        }

    /** Mode names for frequency records, looked up once per ID. */
    fun modeName(mode: String): String {
        val id = mode.trim().toIntOrNull() ?: return mode
        return modeNames.getOrPut(id) {
            val result = call("getMode", "mode" to id.toString())
            result.items().firstOrNull()?.text("modeName") ?: result.text("modeName")
        }
    }

    private fun call(action: String, vararg parameters: Pair<String, String>): Element {
        val envelope = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:tns=\"$NAMESPACE\">")
            append("<soapenv:Body><tns:").append(action).append('>')
            for ((name, value) in parameters) append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
            append("<authInfo>")
            append("<username>").append(escape(auth.username)).append("</username>")
            append("<password>").append(escape(auth.password)).append("</password>")
            append("<appKey>").append(escape(auth.appKey)).append("</appKey>")
            append("<version>latest</version><style>rpc</style>")
            append("</authInfo></tns:").append(action).append("></soapenv:Body></soapenv:Envelope>")
        }
        val response = transport(action, envelope)
        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(response.byteInputStream(Charsets.ISO_8859_1))
        } catch (e: Exception) {
            throw RadioReferenceException("RadioReference sent back something unreadable.")
        }
        val body = document.documentElement.child("Body") ?: throw RadioReferenceException("RadioReference sent back something unreadable.")
        body.child("Fault")?.let { fault ->
            throw RadioReferenceException(fault.text("faultstring").ifEmpty { "RadioReference refused the request." }, fault.text("faultcode"))
        }
        val wrapper = body.elements().firstOrNull() ?: throw RadioReferenceException("RadioReference sent back an empty answer.")
        return wrapper.child("return") ?: wrapper
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val NAMESPACE = "http://api.radioreference.com/soap2"
        const val ENDPOINT = "https://api.radioreference.com/soap2/index.php"

        fun httpPost(action: String, envelope: String): String {
            val connection = URI(ENDPOINT).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                connection.setRequestProperty("SOAPAction", "$NAMESPACE#$action")
                connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
                // Faults come back as HTTP 500 with a SOAP body worth reading.
                val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
                return stream?.use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    ?: throw RadioReferenceException("RadioReference answered HTTP ${connection.responseCode}.")
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun Element.elements(): List<Element> {
    val out = ArrayList<Element>()
    var node = firstChild
    while (node != null) {
        if (node is Element) out += node
        node = node.nextSibling
    }
    return out
}

private fun Element.child(name: String): Element? = elements().firstOrNull { (it.localName ?: it.nodeName.substringAfter(':')) == name }

/** SOAP arrays list their members as children, usually named "item". */
private fun Element.items(): List<Element> = elements()

private fun Element.text(name: String): String = child(name)?.textContent?.trim().orEmpty()

private fun Element.int(name: String): Int = text(name).toIntOrNull() ?: text(name).toDoubleOrNull()?.toInt() ?: 0

private fun Element.decimal(name: String): BigDecimal? = text(name).toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
