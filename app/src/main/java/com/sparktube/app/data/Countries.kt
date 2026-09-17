package com.sparktube.app.data

data class Country(val code: String, val name: String)

object Countries {

    val all: List<Country> = listOf(
        Country("BD", "Bangladesh"),
        Country("IN", "India"),
        Country("US", "United States"),
        Country("GB", "United Kingdom"),
        Country("PK", "Pakistan"),
        Country("AE", "United Arab Emirates"),
        Country("SA", "Saudi Arabia"),
        Country("CA", "Canada"),
        Country("AU", "Australia"),
        Country("DE", "Germany"),
        Country("AF", "Afghanistan"),
        Country("AL", "Albania"),
        Country("DZ", "Algeria"),
        Country("AO", "Angola"),
        Country("AR", "Argentina"),
        Country("AM", "Armenia"),
        Country("AT", "Austria"),
        Country("AZ", "Azerbaijan"),
        Country("BH", "Bahrain"),
        Country("BY", "Belarus"),
        Country("BE", "Belgium"),
        Country("BO", "Bolivia"),
        Country("BA", "Bosnia and Herzegovina"),
        Country("BR", "Brazil"),
        Country("BG", "Bulgaria"),
        Country("KH", "Cambodia"),
        Country("CL", "Chile"),
        Country("CN", "China"),
        Country("CO", "Colombia"),
        Country("CR", "Costa Rica"),
        Country("HR", "Croatia"),
        Country("CZ", "Czechia"),
        Country("DK", "Denmark"),
        Country("DO", "Dominican Republic"),
        Country("EC", "Ecuador"),
        Country("EG", "Egypt"),
        Country("SV", "El Salvador"),
        Country("EE", "Estonia"),
        Country("ET", "Ethiopia"),
        Country("FI", "Finland"),
        Country("FR", "France"),
        Country("GE", "Georgia"),
        Country("GH", "Ghana"),
        Country("GR", "Greece"),
        Country("GT", "Guatemala"),
        Country("HN", "Honduras"),
        Country("HK", "Hong Kong"),
        Country("HU", "Hungary"),
        Country("IS", "Iceland"),
        Country("ID", "Indonesia"),
        Country("IR", "Iran"),
        Country("IQ", "Iraq"),
        Country("IE", "Ireland"),
        Country("IL", "Israel"),
        Country("IT", "Italy"),
        Country("JM", "Jamaica"),
        Country("JP", "Japan"),
        Country("JO", "Jordan"),
        Country("KZ", "Kazakhstan"),
        Country("KE", "Kenya"),
        Country("KW", "Kuwait"),
        Country("LA", "Laos"),
        Country("LV", "Latvia"),
        Country("LB", "Lebanon"),
        Country("LY", "Libya"),
        Country("LT", "Lithuania"),
        Country("LU", "Luxembourg"),
        Country("MY", "Malaysia"),
        Country("MT", "Malta"),
        Country("MX", "Mexico"),
        Country("MD", "Moldova"),
        Country("MA", "Morocco"),
        Country("MM", "Myanmar"),
        Country("NP", "Nepal"),
        Country("NL", "Netherlands"),
        Country("NZ", "New Zealand"),
        Country("NI", "Nicaragua"),
        Country("NG", "Nigeria"),
        Country("MK", "North Macedonia"),
        Country("NO", "Norway"),
        Country("OM", "Oman"),
        Country("PA", "Panama"),
        Country("PY", "Paraguay"),
        Country("PE", "Peru"),
        Country("PH", "Philippines"),
        Country("PL", "Poland"),
        Country("PT", "Portugal"),
        Country("QA", "Qatar"),
        Country("RO", "Romania"),
        Country("RU", "Russia"),
        Country("SN", "Senegal"),
        Country("RS", "Serbia"),
        Country("SG", "Singapore"),
        Country("SK", "Slovakia"),
        Country("SI", "Slovenia"),
        Country("ZA", "South Africa"),
        Country("KR", "South Korea"),
        Country("ES", "Spain"),
        Country("LK", "Sri Lanka"),
        Country("SD", "Sudan"),
        Country("SE", "Sweden"),
        Country("CH", "Switzerland"),
        Country("TW", "Taiwan"),
        Country("TZ", "Tanzania"),
        Country("TH", "Thailand"),
        Country("TN", "Tunisia"),
        Country("TR", "Turkey"),
        Country("UG", "Uganda"),
        Country("UA", "Ukraine"),
        Country("UY", "Uruguay"),
        Country("UZ", "Uzbekistan"),
        Country("VE", "Venezuela"),
        Country("VN", "Vietnam"),
        Country("YE", "Yemen"),
        Country("ZM", "Zambia"),
        Country("ZW", "Zimbabwe")
    )

    private val byCode: Map<String, Country> = all.associateBy { it.code }

    fun nameOf(code: String): String = byCode[code]?.name ?: code

    fun flagOf(code: String): String {
        val upper = code.uppercase()
        if (upper.length != 2) return ""
        return buildString {
            for (ch in upper) {
                val idx = ch.code - 'A'.code
                if (idx in 0..25) {
                    // Regional indicator symbols (flag emoji)
                    append(Character.toChars(0x1F1E6 + idx))
                }
            }
        }
    }
}
