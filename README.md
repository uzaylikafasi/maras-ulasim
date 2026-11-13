<p align="center">
  <img src="docs/banner.png" alt="Mavi harita çizgileri üzerinde otobüs simgesi ve Maraş Ulaşım yazısı" width="100%">
</p>

<h1 align="center">Maraş Ulaşım</h1>

<p align="center">
  Kahramanmaraş otobüsleri için Android uygulaması.<br>
  Hatları haritada canlı izle, durağa yaklaşan otobüsleri gör, favorilerini kaydet.<br>
  İnternet zayıfken ya da yokken çevrimdışı tahminle yola devam et; koyu temayı kullan.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/s%C3%BCr%C3%BCm-1.2.0-2563EB" alt="Sürüm 1.2.0">
  <img src="https://img.shields.io/badge/Android-8.0%2B-2563EB" alt="Android 8.0 ve üzeri">
  <a href="LICENSE"><img src="https://img.shields.io/badge/lisans-MIT-2563EB" alt="MIT lisansı"></a>
</p>

<p align="center">
  <a href="https://github.com/uzaylikafasi/maras-ulasim/releases/tag/v1.2.0">
    <img src="https://img.shields.io/badge/APK_indir-1.2.0-2563EB?style=for-the-badge" alt="APK indir — sürüm 1.2.0">
  </a>
</p>

> **Bağımsız bir uygulamadır. Belediye veya Kahramankart'ın resmî uygulaması değildir.** Veriler kaynağın verdiği gibidir; varış süreleri tahmindir.

## Ekran görüntüleri

<table>
  <tr>
    <td align="center"><a href="docs/ekran/harita.png"><img src="docs/ekran/harita.png" width="170" alt="Koyu temada hat güzergâhı ve canlı otobüsler"></a></td>
    <td align="center"><a href="docs/ekran/durak.png"><img src="docs/ekran/durak.png" width="170" alt="Durak menüsü ve yaklaşan otobüsler"></a></td>
    <td align="center"><a href="docs/ekran/cevrimdisi.png"><img src="docs/ekran/cevrimdisi.png" width="170" alt="Bağlantı yokken çevrimdışı tahmin kartı"></a></td>
    <td align="center"><a href="docs/ekran/ayarlar.png"><img src="docs/ekran/ayarlar.png" width="170" alt="Tema, koyu harita ve çevrimdışı mod ayarları"></a></td>
    <td align="center"><a href="docs/ekran/diger.png"><img src="docs/ekran/diger.png" width="170" alt="Duyurular, hatırlatmalar ve bağlantıların bulunduğu Diğer ekranı"></a></td>
  </tr>
  <tr>
    <td align="center">Canlı otobüsler</td>
    <td align="center">Durak menüsü</td>
    <td align="center">Çevrimdışı tahmin</td>
    <td align="center">Tema ve çevrimdışı ayarları</td>
    <td align="center">Diğer ekranı</td>
  </tr>
</table>

## Özellikler

- **Çevrimdışı mod (yeni, 1.2.0):** Bağlantı kötüyken sağ üstteki bulut düğmesi büyür; dokununca süreler tarifeden ve geçmiş ölçümlerden tahmin edilir. “Çevrimdışı tahmin” kartı bunu açıkça belirtir; tahmin otobüsün gerçek konumu değildir. Model küçük bir dosyadır, günde bir kez kendiliğinden güncellenir. Ayarlar > Çevrimdışı mod: Düğme (varsayılan), Otomatik, Kapalı.
- **Koyu tema (yeni, 1.2.0):** Ayarlar > Tema: Sistem (varsayılan), Açık, Koyu (siyah zemin, sarı vurgu, gri yüzeyler). Koyu temada harita da koyulaşır; istersen “Koyu temada koyu harita” ayarını kapatıp haritayı açık bırakabilirsin.
- **Canlı harita:** Hatları gidiş ve dönüş yönleriyle aynı anda 4 hatta kadar izle. Otobüs işaretleri aracın yönünü gösterir.
- **Durak bilgileri:** Durağa dokunarak yaklaşan otobüsleri gör. “Sadece favorilerim” filtresiyle yalnızca favori hatlarını göster.
- **Yakındaki duraklar:** Yakınındaki durakları ve bu duraklardan geçen hatları bul.
- **Favoriler:** Hat, durak ve plaka kaydet; ana ekran widget'ını kullan.
- **Yolculuk bilgileri:** İki durak arası yolculuk önerilerine, sefer saatlerine ve duyurulara bak.
- **Bildirimler ve takip:** Bildirimler > Otobüs ekle ile durak ve yaklaşan otobüsü seç; kaç dakika kala uyarılacağını belirle. Açık bildirimde tahmin yenilenir, kaynağın bildirdiği son geçilen ve sıradaki duraklar görünür. Arka planda en çok iki saat takip et; Bildirimler > Takip bölümünden izle veya kaldır. Bilinmeyen süre için uyarı verilmez. Saatli okul ve iş hatırlatmaları da burada.
- **Daha hafif harita:** Duraklar yalnız yakından küçük otobüs simgeleriyle görünür; şehir ölçeğinde gizlenir. Seçili durak ayrıca işaretlenir. Telefon konumu yaklaşık 30 saniye arayla sorgulanır.
- **Ayarlar:** Durak/ışık payı, doğrudan otobüs takibi ve görünüm seçenekleri. Süre payları deneysel ve isteğe bağlıdır; [ölçüm notları](docs/eta-arastirma.md).
- **Güncelleme:** Yeni GitHub sürümlerini günde bir kontrol eder; notları gösterir. İndir ve Kur düğmeleriyle Android yükleyicisini açabilir veya bir sürümü atlayabilirsin.
- **Açık kaynak harita:** MapLibre, OpenFreeMap ve OpenStreetMap.

## Kurulum

1. [1.2.0 sürümünün APK dosyasını indir](https://github.com/uzaylikafasi/maras-ulasim/releases/tag/v1.2.0).
2. İndirdiğin APK dosyasını aç.
3. Android istediğinde, dosyayı açtığın uygulamaya “bilinmeyen kaynaklardan yükleme” izni ver ve kurulumu tamamla.

Play Store dışından kurulum sırasında Play Protect uyarı gösterebilir. Android 8.0 ve üzeri gerekir.

## Veri kaynağı

Uygulama verileri [Maraş Ulaşım API](https://mithrixa.com/ulasim-api/docs)'sinden alır. API'nin kaynak kodu: [kahramankart-ulasim-api](https://github.com/uzaylikafasi/kahramankart-ulasim-api).

## Geliştirme ve derleme

Kullanılan teknolojiler: Kotlin, Jetpack Compose, MapLibre Native, OkHttp ve DataStore.

```sh
./gradlew :app:assembleDebug        # geliştirme sürümü
./gradlew :app:testDebugUnitTest    # birim testleri
```

Yayın imzası depoda yoktur. `~/.config/maras-ulasim-imza/signing.properties` dosyası (veya `MARAS_SIGNING` ortam değişkeni) varsa `assembleRelease` imzalı APK üretir. Raspberry Pi gibi ARM64 Linux makinelerde `./build-pi.sh` kullanılabilir.

## Lisans

Bu proje [MIT lisansı](LICENSE) ile sunulmaktadır. Üçüncü taraf lisansları için [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) dosyasına bakabilirsin.
