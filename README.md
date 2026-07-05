# Bandacon — Türkçe Anime CloudStream Reposu

Türkçe altyazılı anime için CloudStream eklenti reposu. İlk kaynak: **Anizm** (anizm.net).

## Kurulum

1. **CloudStream** kur: [pre-release APK](https://github.com/recloudstream/cloudstream/releases/tag/pre-release)
2. CloudStream > **Settings > Extensions > Add repository**
3. Repo URL'i yapıştır:
   ```
   https://raw.githubusercontent.com/Bandaju/bandacon/master/repo.json
   ```
4. Açılan listeden **Anizm** eklentisini yükle.
5. Ana ekrandan ara (örn. "Re:Zero", "Naruto"), animeyi seç, bölüme gir.

## Eklentiler

| Eklenti | Kaynak | Tip | Dil | Kalite |
|---------|--------|-----|-----|--------|
| Anizm | anizm.net | Anime, AnimeMovie | tr (altyazı) | HLS, 1080p'ye kadar |

Her bölüm için mevcut **fansub'lar ayrı akış** olarak listelenir. Kaynak adaptif HLS (master.m3u8); CloudStream oynatıcısı en yüksek varyantı (1080p) otomatik seçer.

## Neden CloudStream

Anizm.net Cloudflare korumalıdır. CloudStream eklentileri **cihaz üzerinde** çalışır ve dahili `CloudflareKiller` (WebView tabanlı challenge çözücü) ile anizm.net'e erişir. Video host'u (anizmplayer.com) Cloudflare korumasız olduğundan akış doğrudan çalışır.

## Geliştirme

Kotlin + CloudStream gradle plugin. `.cs3` dosyaları GitHub Actions ile derlenip `builds` branch'ine yayınlanır (`plugins.json`). Yerel Android SDK gerekmez — CI derler.

Reverse-engineered akış zinciri `Anizm/src/main/kotlin/com/bandaju/Anizm.kt` başında dokümanlıdır.

## Yol haritası

- [ ] Ana sayfa (kategori / popüler listeleme).
- [ ] Çok-fansub kalite/etiket iyileştirmeleri.
- [ ] İkinci Türkçe kaynak (dizi/film).

## Lisans / Atıf

Public domain. Eklenti sistemi ve gradle plugin'i [Aliucord](https://github.com/Aliucord) tabanlıdır; template [recloudstream/TestPlugins](https://github.com/recloudstream/TestPlugins).
