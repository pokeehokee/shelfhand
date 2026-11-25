package app.shelfhand.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedsTest {
    private val podcastBook = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<channel>
  <title>The Clockwork Orchard</title><link>https://janedoe.example/orchard</link><language>en-us</language>
  <itunes:author>Jane Doe</itunes:author><itunes:type>serial</itunes:type>
  <itunes:category text="Fiction"><itunes:category text="Science Fiction"/></itunes:category>
  <description>A free serialized audiobook.</description>
  <item><title>Chapter 1: Seeds</title><enclosure url="https://janedoe.example/1.mp3" type="audio/mpeg" length="1"/><itunes:duration>32:10</itunes:duration><guid>c1</guid></item>
  <item><title>Chapter 2: Roots</title><enclosure url="https://janedoe.example/2.mp3" type="audio/mpeg" length="1"/><itunes:duration>1:01:00</itunes:duration><guid>c2</guid></item>
  <item><title>Chapter 3: Fruit</title><enclosure url="https://janedoe.example/3.mp3" type="audio/mpeg" length="1"/><itunes:duration>1800</itunes:duration><guid>c3</guid></item>
</channel></rss>"""

    @Test fun `a serialized book published as a podcast is one book with the summed length`() {
        val f = Feeds.parse(podcastBook, "https://janedoe.example/feed")
        assertEquals("The Clockwork Orchard", f.title)
        val b = f.books.single()
        assertEquals(listOf("Jane Doe"), b.authors)
        assertEquals((32 * 60 + 10) + 3660 + 1800L, b.seconds)
        assertEquals(3, b.sections)
        assertEquals("English", b.language)
        assertTrue("Fiction" in b.genres)
        assertEquals("https://janedoe.example/feed", b.feedUrl)
    }

    private val torrentFeed = """<?xml version="1.0"?>
<rss version="2.0" xmlns:torrent="http://xmlns.ezrss.it/0.1/">
<channel><title>Indie Audio Releases</title>
  <item><title>Night Train, Book 2</title><link>https://indie.example/night-train-2</link>
    <category>Mystery</category><category>Audiobook</category>
    <enclosure url="https://indie.example/night-train-2.torrent" type="application/x-bittorrent" length="1"/>
    <torrent:magnetURI>magnet:?xt=urn:btih:abc123</torrent:magnetURI><pubDate>Mon, 03 Nov 2025 10:00:00 GMT</pubDate></item>
  <item><title>Lone House</title><link>magnet:?xt=urn:btih:def456</link></item>
  <item><title>Chapter of Nothing</title><link>magnet:?xt=urn:btih:x</link></item>
</channel></rss>"""

    @Test fun `torrent feeds keep one entry per item and carry the magnet or torrent`() {
        val b = Feeds.parse(torrentFeed, "https://indie.example/rss").books
        assertEquals(3, b.size)
        assertEquals("magnet:?xt=urn:btih:abc123", b[0].torrentUrl)
        assertNull(b[0].downloadUrl)
        assertEquals(listOf("Mystery", "Audiobook"), b[0].genres)
        assertEquals(2025, b[0].year)
        assertEquals(SeriesGuess("Night Train", 2.0), b[0].series)
        assertEquals("magnet:?xt=urn:btih:def456", b[1].torrentUrl)
        assertNull(b[1].pageUrl)
        assertEquals("Indie Audio Releases", b[1].collection)
    }

    private val opds = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dcterms="http://purl.org/dc/terms/">
  <title>Free Classics</title>
  <entry><id>urn:1</id><title>Persuasion</title><author><name>Jane Austen</name></author>
    <category term="fiction" label="Romance"/><dcterms:language>en</dcterms:language>
    <link rel="http://opds-spec.org/acquisition" type="application/zip" href="/books/persuasion.zip"/>
    <link rel="alternate" type="text/html" href="/books/persuasion"/></entry>
</feed>"""

    @Test fun `atom and opds entries read their acquisition link against the feed address`() {
        val b = Feeds.parse(opds, "https://classics.example/opds/root.xml").books.single()
        assertEquals("Persuasion", b.title)
        assertEquals(listOf("Jane Austen"), b.authors)
        assertEquals(listOf("Romance"), b.genres)
        assertEquals("English", b.language)
        assertEquals("https://classics.example/books/persuasion.zip", b.downloadUrl)
        assertEquals("https://classics.example/books/persuasion", b.pageUrl)
    }

    @Test fun `a page's advertised feeds are found and resolved, other links ignored`() {
        val html = """<html><head>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/rss+xml" title="Audio" href="/feed/audio">
            <link type='application/atom+xml' href='https://cdn.example/atom.xml' rel='alternate'>
            <link rel="alternate" hreflang="fr" href="/fr/">
            </head></html>"""
        assertEquals(listOf("https://site.example/feed/audio", "https://cdn.example/atom.xml"), Feeds.discover(html, "https://site.example/books/"))
    }

    @Test fun `feeds are told apart from pages, and odd durations are unknown`() {
        assertTrue(Feeds.looksLikeFeed(torrentFeed))
        assertTrue(!Feeds.looksLikeFeed("<!doctype html><html><head>"))
        assertEquals(90L, Feeds.duration("1:30"))
        assertNull(Feeds.duration("about an hour"))
        assertNull(Feeds.duration("0"))
    }

    @Test fun `a feed with a doctype is refused rather than read`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        assertTrue(runCatching { Feeds.parse(evil, "https://e.example/") }.isFailure)
    }
}
