package za.co.cttrains.app

import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import za.co.cttrains.app.data.*
import za.co.cttrains.core.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

@RunWith(AndroidJUnit4::class)
class NativeDataFeaturesTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val features=FeatureStore(context)
    @Before fun clear()=runBlocking { features.store.clearPersonalData() }
    @After fun finish()=runBlocking { features.store.clearPersonalData();PortableFiles(context,features).clearPrivatePhotos() }
    @Test fun largeBusGeometryUsesEncryptedFilesAndSmallRoomRows()=runBlocking {
        val source=BusSources.MYCITI_MAP
        val shape=BusShape("101","Synthetic storage test",(0 until 55000).map { GeoPoint(-33.90+it*.000001,18.6+it*.000001) },source)
        val data=BusSnapshot(TransportService.MYCITI,System.currentTimeMillis(),listOf(BusRouteDocument("101","101","https://www.myciti.org.za/en/timetables/route-timetables/?route=101")),listOf(BusStopPoint("TEST","Test",shape.points.first(),listOf("101"),source)),listOf(shape),emptyList(),"Test")
        assertTrue(features.json.encodeToString(data).toByteArray().size>2*1024*1024)
        features.saveSnapshot(BusSources.validate(data))
        assertEquals(data,features.snapshot(TransportService.MYCITI))
        val row=TransitDatabase.get(context).dao().value("bus-MYCITI")!!
        assertTrue(row.encryptedValue.length<1000)
        assertFalse(row.encryptedValue.contains("Synthetic storage test"))
        assertTrue(File(context.filesDir,"large-values").listFiles().orEmpty().all { !it.readText().contains("Synthetic storage test") })
    }
    @Test fun realGoldenArrowPdfExtractsItsColumnsAndWeekdayRestrictions() {
        val encoded=InstrumentationRegistry.getInstrumentation().context.assets.open("golden-arrow-pdf.base64").bufferedReader().use { it.readText() }
        val bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)
        PDFBoxResourceLoader.init(context)
        val text=PDDocument.load(bytes).use { d->PDFTextStripper().apply { sortByPosition=true;pageEnd="\u000c" }.getText(d) }
        val route=BusRouteDocument("004401","Airport Industria","https://www.gabs.co.za/Pdf/Apdf/sample.pdf","2026-09-28",pdf=true)
        val friday=GoldenTimetables.parse(text,route,"Friday",System.currentTimeMillis())
        assertTrue(friday.trips.any { it.calls.first().time=="05:30" && it.calls.last().time=="06:20" })
        assertFalse(friday.trips.any { it.calls.first().time=="05:30" && it.calls.last().time=="06:30" })
        assertTrue(friday.trips.none { t->t.calls.any { it.name=="ROBERT SOBUKWE RD" } })
    }
    @Test fun portableProfileRestoresPhotoWithoutRestoringRemoteSessions()=runBlocking {
        val files=PortableFiles(context,features)
        val image=File(File(context.cacheDir,"shared").apply { mkdirs() },"test-photo.jpg")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) };bitmap.recycle()
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",image)
        val photo=files.addPhoto(uri)
        val spot=MarkedSpot(java.util.UUID.randomUUID().toString(),"Campus stop",TransportService.GOLDEN_ARROW,GeoPoint(-33.9,18.6),"Stop",photo=photo,createdAt=System.currentTimeMillis())
        features.update { it.copy(localName="Native test",spots=listOf(spot),beta=it.beta.copy(hostedSharing=true)) }
        features.saveSession(MatrixSession("https://matrix.example.test","@test:example.test","test-private-session-token","test"))
        features.saveBackupLink(BackupLink("content://test/backup","test-private-auto-backup-password"))
        features.store.writeLocal("wa-alpha-config","test-private-openwa-key")
        features.store.writeLocal("wa-alpha-cache","test-private-whatsapp-post")
        val password="test-only export password"
        val raw=files.export(password)
        val plain=PortableCrypto.decrypt(raw,password.toCharArray())
        assertFalse(plain.contains("test-private-session-token"));assertFalse(plain.contains("test-private-auto-backup-password"))
        assertFalse(plain.contains("test-private-openwa-key"));assertFalse(plain.contains("test-private-whatsapp-post"))
        val inspected=files.inspect(raw,password)
        features.store.clearPersonalData();files.clearPrivatePhotos();files.restore(inspected)
        assertNull(features.store.readLocal("wa-alpha-config"));assertNull(features.store.readLocal("wa-alpha-cache"))
        val restored=features.extras()
        assertEquals("Native test",restored.localName);assertFalse(restored.beta.hostedSharing);assertNull(features.session());assertNull(features.backupLink())
        assertNotEquals(photo,restored.spots.first().photo)
        assertEquals(64,files.bitmap(restored.spots.first().photo!!)!!.width)
    }
    @Test fun belharPdfContinuationPagesAndFridayTripsRemainReadable() {
        val encoded=InstrumentationRegistry.getInstrumentation().context.assets.open("belhar-golden-pdf.base64").bufferedReader().use { it.readText() }
        val bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)
        PDFBoxResourceLoader.init(context)
        val text=PDDocument.load(bytes).use { d->PDFTextStripper().apply { sortByPosition=true;pageEnd="\u000c" }.getText(d) }
        val document=BusRouteDocument("003001","BELHAR-CAPE TOWN","https://www.gabs.co.za/Pdf/Bpdf/BELHAR___CAPE_TOWN_from_20260928_to_20261004_003001.pdf","2026-09-28","2026-10-04",true)
        val friday=GoldenTimetables.parse(text,document,"Friday",1)
        assertTrue(friday.trips.size>30)
        assertTrue(friday.trips.any { it.calls.first().name=="CAPE TOWN" && it.calls.first().time=="11:00" && it.calls.last().time=="12:25" })
        assertTrue(friday.trips.all { it.calls.zipWithNext().all { (a,b)->a.time<=b.time } })
        assertTrue(friday.trips.none { trip->trip.calls.any { it.name=="ROBERT SOBUKWE RD" } })
    }
}
