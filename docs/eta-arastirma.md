# Varış süresi araştırması — 5 Kasım 2026

## Elimizde ne var?

Android, `/v1/stops/{id}` yanıtındaki `remaining_time` ve sayısal `timeToStop` alanlarını kullanıyor. Canlı örnekte 91 saniye ile “1 dk 31 sn” eşleşti. API sunucusu `BusStop` yanıtını 10 saniye önbellekleyip aktarıyor; kendi ETA modeli yok. `fetched_at` sunucunun aldığı zamanı gösterir, GPS ölçüm zamanı değildir. Kaynağın Google sürüş tahmini kullandığı iddiası kod veya yayımlanmış yöntemle doğrulanamadı.

Samet'in uygulama tarafında eklediği seçenekler: sıradaki durak ile hedef arasındaki durak sayısı × 15 saniye ve konum/durak çizgisinin 30 m yakınındaki ışık sayısı × 20 saniye. Gömülü OSM ışıkları yanında cihazda tekrar eden duruşlardan öğrenilen adaylar var. Trafikte beklemek, yolcu almak ve kırmızı ışıkta durmak konumdan kesin ayrılamaz. Duraklar arasındaki düz çizgi gerçek yol değildir; harita geometrisinin temizlenmesi bu tahmini otomatik olarak yol geometrisine dönüştürmez. Terminalde kalkış saati yoksa süre hâlâ bilinmiyor; pay eklenmiyor.

## Ölçüm yöntemi ve sınırları

6968 (Demokrasi Meydanı), 6826 (Gözde) ve 6796 (Büyükşehir Belediyesi) durakları yaklaşık 30 saniyede bir örnekleniyor. Ham yanıtlar ve analiz dış raporun `kanitlar/eta-live.jsonl`, `eta-lines.json`, `eta-summary.json` dosyalarında. İlk Python HTTP istemcisi 403 aldı; curl ile başarılı toplama başladı. Başarısız istekler de kayıtlıdır.

Bu bir saha doğruluğu ölçümü değildir. Yolcu iniş/biniş zamanı ve gerçek GPS zaman damgası yoktur. Ölçülen olay: araç önce duraktan 100 m uzaktayken, sonraki en çok 90 saniye içindeki örnekte 50 m içine girer ve önceki/sonraki durak kimliklerinden biri hedefi gösterir. İki örneğin orta noktası yaklaşık varış; yarı aralık zaman belirsizliğidir. Her durak/plaka için bir olay alınır. Tahmin, bu olaydan 1–10 dakika önceki, kaynak ETA'sı 1–10 dakika olan ilk kullanılabilir kayıttır. Aynı kaynak zaman damgası tekrar sayılmaz. İşaretli hata = tahmin − gözlenen kalan süre; negatif değer erken tahmindir. MAE mutlak hataların ortalamasıdır.

<!-- OLCUM -->
İlk değerlendirme penceresi 2026-11-05T14:00:20.727242+00:00 – 2026-11-05T14:09:16.708782+00:00 (UTC). 54 başarılı durak yanıtı, 1890 araç satırı, 26 yaklaşık varış olayı. Kaynak MAE 194.62 sn; +15 sn/durak MAE 149.67 sn; yalnız gömülü ışık payı MAE 184.55 sn; ikisi birlikte 144.25 sn. Bu kısa pencere aynı cadde ve birkaç aracın birden fazla durağını içerir; bağımsız, şehir geneline yayılan yolculuklar değildir. Toplama çalışma boyunca sürer; son sonuç sürüm raporunda ve bu belgede güncellenir.
<!-- /OLCUM -->

Işık denemesi yalnız gömülü bilinen ışıklarla, uygulamanın düz durak çizgisi yaklaşımıyla yapıldı; cihazın öğrendiği ışıklar ve gerçek kırmızı fazı ölçülmedi. Bu nedenle +20 saniyenin doğruluğu doğrulanmış değildir. +15 saniye bu küçük örnekte ortalama hatayı azaltsa da her otobüs her durakta durmaz; yoğun saat, yön, terminal beklemesi ve kaynak hatası birbirine karışır. Sabit payın genellenmesi için yeterli kanıt yok.

## Seçenekler

| Yöntem | Beklenen yarar | Maliyet / risk |
| --- | --- | --- |
| Kaynak ETA'yı kısa süreli yumuşatma | Kartta sıçramayı azaltır | Gerçek değişimi geciktirir; varışa yaklaşırken ham tahmine dönmek gerekir. Doğruluğu kendiliğinden artırmaz. |
| GPS geçmişinden durak kesim süreleri | Yerel trafik ve hat hızını öğrenebilir | Sunucuda zamanlı, yön/sefer eşleşmiş konum geçmişi gerekir. Pi'de saklama, örnekleme ve kalite kontrol maliyeti var. |
| Durak bekleme süreleri | Durak payını gerçekten ölçer | Duruşu ışık/kuyruktan ayırmak gerekir. Hat ve saat bazında örnek sayısı gerekir. |
| Gün ve saat profilleri | Yoğun saat etkisini yakalar | Birkaç haftalık geçmiş, tatil/hafta sonu ayrımı gerekir. |
| Kullanıcı “geldi/geçti” bildirimi | Saha varışını doğrulayabilir | Henüz ürün kararı yok; yanlış/tekrarlı bildirim, mahremiyet ve kötüye kullanım kontrolü gerekir. |
| Sabit +15 sn/durak, +20 sn/ışık | Ucuz, açıklanabilir | Küçük örneğe aşırı uyum, kullanılmayan durak, aynı kavşak ve yol dışı çizgi hataları. |

## Karar

Bu sürümde yeni bir ETA kestirimi uygulanmadı. Pay seçenekleri korundu; yeni kurulumlarda varsayılan kapalı ve deneysel olarak açıklanıyor. Kullanıcı daha önce açmışsa tercihi korunuyor. “Işık yakınında bekliyor” ifadesi kırmızı fazın bilindiğini iddia etmiyor. Bildirim eşiği bu deneysel paylardan değil taze kaynak ETA'sından hesaplanmalı.

Önerilen sonraki adım: backend'i bu sürümde değiştirmeden, birkaç gün farklı hat/yön/saatlerde aynı kayıt yöntemi ve bağımsız gerçek durak varış gözlemleriyle örnek toplamak. Eğitim ve değerlendirme günlerini ayırmak; 1/3/5/10 dakika ufuklarında örnek sayısı, MAE, medyan/P90 hata, erken/geç tahmin ve veri bulunabilirliği raporlamak. Sonra hat/saat bazlı medyan durak beklemesi denenebilir; ham kaynakla aynı olaylarda karşılaştırılmalı. Sadece ortalama iyileşme değil erken uyarıyı kaçırma oranı da değerlendirilmelidir.

Kaynaklar: [GTFS Realtime referansı: zaman, belirsizlik ve varış güncellemeleri](https://gtfs.org/documentation/realtime/reference/), [GTFS Realtime iyi uygulamalar: güncelleme zamanı ve geçmiş duraklar](https://gtfs.org/documentation/realtime/best-practices/), [ABD Ulaştırma Bakanlığı: toplu taşıma tahminlerinin değerlendirilmesi](https://rosap.ntl.bts.gov/view/dot/77190).
