package com.lagradost.cloudstream.desktop.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal object DashManifest {
    private val baseEncoder = Base64.getUrlEncoder().withoutPadding()
    private val baseDecoder = Base64.getUrlDecoder()
    private val templateToken = Regex("""\$\$|\$[A-Za-z][A-Za-z0-9]*(?:%[0-9]+[A-Za-z])?\$""")
    private val urlAttributes = mapOf(
        "SegmentTemplate" to listOf("media", "initialization", "index", "bitstreamSwitching"),
        "SegmentURL" to listOf("media", "index"),
        "Initialization" to listOf("sourceURL"),
        "RepresentationIndex" to listOf("sourceURL"),
        "BitstreamSwitching" to listOf("sourceURL")
    )

    fun rewrite(bytes: ByteArray, responseUrl: HttpUrl, proxyBase: String): ByteArray {
        val builder = DocumentBuilderFactory.newDefaultInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
        val document = builder.parse(bytes.inputStream())
        val mpd = document.documentElement
        require(mpd.localName == "MPD")
        val manifestBase = proxyBase + baseReference(responseUrl.toString())
        val elements = document.getElementsByTagNameNS(mpd.namespaceURI, "*")
        var hasManifestBase = false
        repeat(elements.length) { index ->
            val element = elements.item(index) as Element
            when (element.localName) {
                "BaseURL" -> {
                    val reference = baseReference(element.textContent.trim())
                    element.textContent = if (element.parentNode === mpd) {
                        hasManifestBase = true
                        manifestBase + reference
                    } else reference
                }
                "Location" -> if (element.parentNode === mpd) {
                    val reference = element.textContent.trim()
                    if (reference.isNotEmpty()) element.textContent = manifestBase + resourceReference(reference)
                }
                else -> urlAttributes[element.localName]?.forEach { attribute ->
                    val reference = element.getAttribute(attribute).trim()
                    if (reference.isNotEmpty()) {
                        element.setAttribute(attribute, resourceReference(reference, element.localName == "SegmentTemplate"))
                    }
                }
            }
        }
        if (!hasManifestBase) {
            val name = mpd.prefix?.let { "$it:BaseURL" } ?: "BaseURL"
            val base = document.createElementNS(mpd.namespaceURI, name).apply { textContent = manifestBase }
            val next = generateSequence(mpd.firstChild) { it.nextSibling }
                .filterIsInstance<Element>()
                .firstOrNull { it.namespaceURI != mpd.namespaceURI || it.localName != "ProgramInformation" }
            mpd.insertBefore(base, next)
        }

        val output = ByteArrayOutputStream()
        TransformerFactory.newDefaultInstance().newTransformer().transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    }

    fun resolve(rawPath: String, rawQuery: String?): HttpUrl? {
        var path = rawPath
        var upstream: HttpUrl? = null
        try {
            while (path.startsWith('b')) {
                val separator = path.indexOf('/')
                if (separator < 0) return null
                val reference = baseDecoder.decode(path.substring(1, separator)).toString(Charsets.UTF_8)
                upstream = if (upstream == null) reference.toHttpUrlOrNull() else upstream.resolve(reference)
                if (upstream == null) return null
                path = path.substring(separator + 1)
            }
            val base = upstream ?: return null
            val resource = when {
                path.isEmpty() -> ""
                path.startsWith('r') -> URLDecoder.decode(path.substring(1).replace("+", "%2B"), Charsets.UTF_8)
                else -> return null
            }
            return base.resolve(resource + (rawQuery?.let { "?$it" } ?: ""))
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    // VLC appends directory components intact. Decode each reference before applying its original URL semantics.
    private fun baseReference(reference: String): String = "b${baseEncoder.encodeToString(reference.toByteArray())}/"

    private fun resourceReference(reference: String, templated: Boolean = false): String {
        val path = reference.substringBefore('#').substringBefore('?')
        return buildString {
            append('r')
            var start = 0
            if (templated) {
                for (token in templateToken.findAll(path)) {
                    append(encodePath(path.substring(start, token.range.first)))
                    append(token.value)
                    start = token.range.last + 1
                }
            }
            append(encodePath(path.substring(start)))
            append(reference.substring(path.length))
        }
    }

    private fun encodePath(path: String): String = URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
}
