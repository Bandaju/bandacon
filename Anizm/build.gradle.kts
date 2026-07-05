// Use an integer for version numbers
version = 1

cloudstream {
    authors     = listOf("Bandaju")
    language    = "tr"
    description  = "Anizm - Türkçe altyazılı anime (anizm.net). Fansub seçenekleriyle HLS akışı, 1080p'ye kadar."

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
    **/
    status  = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://www.google.com/s2/favicons?domain=anizm.net&sz=%size%"
}
