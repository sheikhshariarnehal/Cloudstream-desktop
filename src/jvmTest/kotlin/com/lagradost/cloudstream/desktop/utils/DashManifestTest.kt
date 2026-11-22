package com.lagradost.cloudstream.desktop.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashManifestTest {
    private val manifestUrl = "https://origin.example/cdn/current/manifest.mpd?old=1".toHttpUrl()
    private val proxyBase = "http://127.0.0.1:1234/proxy/session/_dash/".toHttpUrl()
    private val namespace = "urn:mpeg:dash:schema:mpd:2011"

    @Test
    fun inheritedTemplatesResolveAgainstEveryBaseUrlAlternative() {
        val mpd = rewrite(
            """<d:MPD xmlns:d="$namespace" xmlns:custom="urn:custom">
                <d:ProgramInformation/>
                <d:BaseURL serviceLocation="primary">../publisher/</d:BaseURL>
                <d:BaseURL serviceLocation="backup">http://backup.example/redundant/</d:BaseURL>
                <d:Period>
                    <d:BaseURL>../period/</d:BaseURL>
                    <d:AdaptationSet>
                        <d:BaseURL>audio/?base=token</d:BaseURL>
                        <d:SegmentTemplate media="../${'$'}RepresentationID${'$'}/${'$'}Number%05d${'$'}.m4s?sig=a%2Fb+c%3D&amp;track=${'$'}RepresentationID${'$'}"
                            initialization="/init/${'$'}RepresentationID${'$'}.mp4?sig=x%26y"
                            index="//cache.example/media/${'$'}Number${'$'}.idx"/>
                        <d:Representation id="audio+1">
                            <d:BaseURL>renditions/</d:BaseURL>
                            <custom:BaseURL>https://extension.example/unchanged</custom:BaseURL>
                        </d:Representation>
                    </d:AdaptationSet>
                </d:Period>
            </d:MPD>"""
        )
        val bases = mpd.elements("BaseURL")
        assertEquals(listOf("primary", "backup"), bases.take(2).map { it.getAttribute("serviceLocation") })
        val template = mpd.elements("SegmentTemplate").single()
        val media = template.getAttribute("media")
        val initialization = template.getAttribute("initialization")
        val index = template.getAttribute("index")
        assertTrue(media.contains("\$Number%05d\$"))
        assertTrue(media.contains("\$RepresentationID\$"))
        val origins = listOf("https://origin.example" to "/cdn", "http://backup.example" to "")
        for ((root, origin) in bases.take(2).zip(origins)) {
            val (host, path) = origin
            val base = bases.drop(2).fold(root.textContent.toHttpUrl()) { url, child ->
                assertNotNull(url.resolve(child.textContent))
            }
            assertEquals(
                "$host$path/period/audio/audio+1/00007.m4s?sig=a%2Fb+c%3D&track=audio+1",
                target(base, media.replace("\$Number%05d\$", "00007").replace("\$RepresentationID\$", "audio+1"))
            )
            assertEquals(
                "$host/init/audio+1.mp4?sig=x%26y",
                target(base, initialization.replace("\$RepresentationID\$", "audio+1"))
            )
            assertEquals("${host.substringBefore("://")}://cache.example/media/7.idx", target(base, index.replace("\$Number\$", "7")))
        }
        assertEquals("https://extension.example/unchanged", mpd.getElementsByTagNameNS("urn:custom", "BaseURL").item(0).textContent)
    }

    @Test
    fun rangeBasedSegmentsKeepTheirUrlsAndRanges() {
        val mpd = rewrite(
            """<MPD xmlns="$namespace">
                <Period>
                    <AdaptationSet>
                        <Representation>
                            <BaseURL>https://files.example/archive/track.mp4?sig=base%2Ftoken</BaseURL>
                            <SegmentBase indexRange="500-700">
                                <Initialization range="0-499"/>
                                <RepresentationIndex sourceURL="?index=1" range="500-700"/>
                            </SegmentBase>
                        </Representation>
                        <Representation>
                            <SegmentList><SegmentURL media="chunk%2Fpart.m4s?token=%252F&amp;url=x+y" mediaRange="10-99"
                                index="https://index.example/range.idx?sig=123" indexRange="0-9"/></SegmentList>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>"""
        )
        val (manifestBase, trackBase) = mpd.elements("BaseURL").map { it.textContent }
        val root = manifestBase.toHttpUrl()
        assertEquals(namespace, mpd.elements("BaseURL").first().namespaceURI)
        val track = assertNotNull(root.resolve(trackBase))
        assertEquals("https://files.example/archive/track.mp4?sig=base%2Ftoken", target(track, ""))
        val init = mpd.elements("Initialization").single()
        assertEquals("0-499", init.getAttribute("range"))
        val index = mpd.elements("RepresentationIndex").single()
        assertEquals("500-700", index.getAttribute("range"))
        assertEquals("https://files.example/archive/track.mp4?index=1", target(track, index.getAttribute("sourceURL")))
        val segment = mpd.elements("SegmentURL").single()
        assertEquals("10-99", segment.getAttribute("mediaRange"))
        assertEquals("0-9", segment.getAttribute("indexRange"))
        assertEquals("https://origin.example/cdn/current/chunk%2Fpart.m4s?token=%252F&url=x+y", target(root, segment.getAttribute("media")))
        assertEquals("https://index.example/range.idx?sig=123", target(root, segment.getAttribute("index")))
    }

    @Test
    fun emptyOptionalInitializationDoesNotBecomeARequest() {
        val mpd = rewrite(
            """<MPD xmlns="$namespace"><Period><AdaptationSet>
                <SegmentTemplate media="chunk-${'$'}Number${'$'}.m4s" initialization=""/>
            </AdaptationSet></Period></MPD>"""
        )
        assertEquals("", mpd.elements("SegmentTemplate").single().getAttribute("initialization"))
    }

    @Test
    fun rejectsDocumentTypeDeclarations() {
        val xml = """<!DOCTYPE MPD SYSTEM "file:/unused.dtd"><MPD xmlns="$namespace"/>"""
        assertFailsWith<SAXParseException> {
            DashManifest.rewrite(xml.toByteArray(), manifestUrl, proxyBase.toString())
        }
    }

    private fun rewrite(xml: String): Element {
        val rewritten = DashManifest.rewrite(xml.toByteArray(), manifestUrl, proxyBase.toString())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(rewritten.inputStream()).documentElement
    }

    private fun target(base: HttpUrl, reference: String): String {
        val local = assertNotNull(base.resolve(reference))
        assertEquals(proxyBase.host, local.host)
        assertTrue(local.encodedPath.startsWith(proxyBase.encodedPath))
        return assertNotNull(DashManifest.resolve(local.encodedPath.removePrefix(proxyBase.encodedPath), local.encodedQuery)).toString()
    }

    private fun Element.elements(name: String): List<Element> {
        val elements = getElementsByTagNameNS(namespace, name)
        return List(elements.length) { elements.item(it) as Element }
    }
}
