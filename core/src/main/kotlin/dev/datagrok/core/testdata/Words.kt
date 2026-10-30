package dev.datagrok.core.testdata

import java.text.Normalizer

/**
 * First and last names that belong together; [familyFirst] cultures write the family name first in a full name, and
 * [middle] names go between (Vietnamese full names usually have one).
 */
internal class NamePool(
    val first: List<String>,
    val last: List<String>,
    val familyFirst: Boolean,
    val weight: Int,
    val middle: List<String> = emptyList(),
)

/** The compact word lists fake values are built from. Kept small on purpose: variety comes from combining them. */
internal object Words {
    val namePools =
        listOf(
            NamePool(
                first = listOf(
                    "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda", "William", "Elizabeth", "David",
                    "Barbara", "Richard", "Susan", "Joseph", "Jessica", "Thomas", "Sarah", "Daniel", "Karen", "Matthew", "Emily",
                    "Andrew", "Olivia", "Ethan", "Emma", "Noah", "Ava", "Liam", "Sophia", "Lucas", "Chloe", "Jack", "Grace",
                ),
                last = listOf(
                    "Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Wilson", "Anderson", "Taylor", "Thomas",
                    "Moore", "Martin", "Jackson", "Thompson", "White", "Harris", "Clark", "Lewis", "Walker", "Young", "Allen",
                    "King", "Wright", "Scott", "Green", "Baker", "Adams", "Nelson", "Hill", "Campbell", "Mitchell", "Carter",
                ),
                familyFirst = false,
                weight = 30,
            ),
            NamePool(
                first = listOf(
                    "Anh", "Bảo", "Châu", "Dũng", "Duy", "Giang", "Hà", "Hải", "Hằng", "Hiếu", "Hoa", "Hùng", "Huy", "Khánh",
                    "Khoa", "Lan", "Linh", "Long", "Mai", "Minh", "My", "Nam", "Ngọc", "Nhung", "Phong", "Phúc", "Phương",
                    "Quân", "Quang", "Sơn", "Tâm", "Thảo", "Thành", "Thư", "Trang", "Trung", "Tuấn", "Tú", "Việt", "Vy", "Yến",
                ),
                last = listOf(
                    "Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ", "Hồ", "Ngô",
                    "Dương", "Lý", "Trương", "Đinh", "Mai", "Lâm",
                ),
                familyFirst = true,
                weight = 25,
                middle = listOf("Văn", "Thị", "Đức", "Thanh", "Quốc", "Gia", "Hữu", "Xuân", "Thu", "Kim", "Hoàng", "Ngọc"),
            ),
            NamePool(
                first = listOf(
                    "José", "María", "Carlos", "Lucía", "Javier", "Sofía", "Miguel", "Valentina", "Diego", "Camila", "Alejandro",
                    "Isabella", "Mateo", "Gabriela", "Pablo", "Daniela",
                ),
                last = listOf(
                    "García", "Rodríguez", "Martínez", "Hernández", "López", "González", "Pérez", "Sánchez", "Ramírez", "Torres",
                    "Flores", "Rivera", "Gómez", "Díaz", "Morales", "Ortiz",
                ),
                familyFirst = false,
                weight = 12,
            ),
            NamePool(
                first = listOf("Wei", "Fang", "Jing", "Lei", "Min", "Xiu", "Yang", "Hui", "Jun", "Li", "Na", "Tao", "Ying", "Hao"),
                last = listOf("Wang", "Li", "Zhang", "Liu", "Chen", "Yang", "Huang", "Zhao", "Wu", "Zhou", "Xu", "Sun", "Ma", "Zhu"),
                familyFirst = true,
                weight = 8,
            ),
            NamePool(
                first = listOf("Haruto", "Yui", "Sota", "Hina", "Ren", "Aoi", "Yuto", "Sakura", "Kaito", "Mei", "Riku", "Yuna"),
                last = listOf("Sato", "Suzuki", "Takahashi", "Tanaka", "Watanabe", "Ito", "Yamamoto", "Nakamura", "Kobayashi", "Kato"),
                familyFirst = false,
                weight = 8,
            ),
            NamePool(
                first = listOf("Aarav", "Priya", "Vivaan", "Ananya", "Arjun", "Diya", "Rohan", "Isha", "Rahul", "Kavya", "Vikram", "Neha"),
                last = listOf("Sharma", "Patel", "Singh", "Kumar", "Gupta", "Reddy", "Iyer", "Nair", "Mehta", "Rao", "Joshi", "Das"),
                familyFirst = false,
                weight = 8,
            ),
            NamePool(
                first = listOf("Lukas", "Léa", "Felix", "Manon", "Jonas", "Camille", "Hugo", "Lena", "Louis", "Clara", "Paul", "Inès"),
                last = listOf("Müller", "Schmidt", "Schneider", "Fischer", "Weber", "Dubois", "Moreau", "Laurent", "Bernard", "Lefèvre", "Wagner", "Becker"),
                familyFirst = false,
                weight = 9,
            ),
        )

    val cities =
        listOf(
            "Hà Nội", "Hồ Chí Minh City", "Đà Nẵng", "Hải Phòng", "Cần Thơ", "Huế", "Nha Trang", "Đà Lạt", "Vũng Tàu", "Quy Nhơn",
            "New York", "Los Angeles", "Chicago", "Houston", "Seattle", "San Francisco", "Boston", "Austin", "Denver", "Miami",
            "Toronto", "Vancouver", "Mexico City", "São Paulo", "Buenos Aires", "Lima", "Bogotá", "London", "Manchester",
            "Dublin", "Paris", "Lyon", "Berlin", "Munich", "Hamburg", "Amsterdam", "Brussels", "Madrid", "Barcelona", "Lisbon",
            "Rome", "Milan", "Vienna", "Zurich", "Prague", "Warsaw", "Stockholm", "Oslo", "Copenhagen", "Helsinki", "Istanbul",
            "Dubai", "Cairo", "Nairobi", "Lagos", "Cape Town", "Mumbai", "Delhi", "Bangalore", "Bangkok", "Singapore",
            "Kuala Lumpur", "Jakarta", "Manila", "Hong Kong", "Shanghai", "Beijing", "Seoul", "Busan", "Tokyo", "Osaka",
            "Sydney", "Melbourne", "Auckland",
        )

    val regions =
        listOf(
            "California", "Texas", "New York", "Florida", "Washington", "Illinois", "Ontario", "Quebec", "British Columbia",
            "Bavaria", "Île-de-France", "Catalonia", "Lombardy", "Scotland", "New South Wales", "Victoria", "Queensland",
            "Hà Nội", "Hồ Chí Minh", "Đà Nẵng", "Khánh Hòa", "Lâm Đồng", "Quảng Ninh", "Bình Dương", "Đồng Nai", "Kanagawa",
            "Osaka", "Maharashtra", "Karnataka", "Gyeonggi",
        )

    /** Country name to its ISO 3166-1 alpha-2 code. */
    val countries =
        listOf(
            "Vietnam" to "VN", "United States" to "US", "Canada" to "CA", "Mexico" to "MX", "Brazil" to "BR", "Argentina" to "AR",
            "Chile" to "CL", "Colombia" to "CO", "Peru" to "PE", "United Kingdom" to "GB", "Ireland" to "IE", "France" to "FR",
            "Germany" to "DE", "Netherlands" to "NL", "Belgium" to "BE", "Spain" to "ES", "Portugal" to "PT", "Italy" to "IT",
            "Switzerland" to "CH", "Austria" to "AT", "Poland" to "PL", "Czechia" to "CZ", "Sweden" to "SE", "Norway" to "NO",
            "Denmark" to "DK", "Finland" to "FI", "Greece" to "GR", "Turkey" to "TR", "Ukraine" to "UA", "Romania" to "RO",
            "Egypt" to "EG", "Nigeria" to "NG", "Kenya" to "KE", "South Africa" to "ZA", "Morocco" to "MA", "Israel" to "IL",
            "United Arab Emirates" to "AE", "Saudi Arabia" to "SA", "India" to "IN", "Pakistan" to "PK", "Bangladesh" to "BD",
            "Thailand" to "TH", "Malaysia" to "MY", "Singapore" to "SG", "Indonesia" to "ID", "Philippines" to "PH",
            "Cambodia" to "KH", "Laos" to "LA", "China" to "CN", "Japan" to "JP", "South Korea" to "KR", "Taiwan" to "TW",
            "Australia" to "AU", "New Zealand" to "NZ",
        )

    val currencies =
        listOf(
            "USD", "EUR", "VND", "GBP", "JPY", "CNY", "KRW", "SGD", "THB", "MYR", "IDR", "PHP", "INR", "AUD", "NZD", "CAD",
            "CHF", "SEK", "NOK", "DKK", "PLN", "CZK", "BRL", "MXN", "ARS", "ZAR", "AED", "SAR", "TRY", "HKD",
        )

    val languages = listOf("en", "vi", "fr", "de", "es", "pt", "it", "nl", "ja", "ko", "zh", "th", "id", "ms", "hi", "ru", "pl", "sv", "tr", "ar")

    val statuses = listOf("active", "inactive", "pending", "suspended", "archived", "draft", "completed", "cancelled")

    val colors =
        listOf(
            "red", "orange", "yellow", "green", "blue", "indigo", "violet", "purple", "pink", "brown", "black", "white", "gray",
            "silver", "gold", "beige", "navy", "teal", "olive", "maroon", "turquoise", "coral", "salmon", "lavender", "mint",
            "crimson", "ivory", "charcoal",
        )

    val emailDomains =
        listOf(
            "gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "icloud.com", "proton.me", "mail.com", "aol.com",
            "fpt.com.vn", "vng.com.vn", "example.com", "example.org", "example.net", "company.io", "acme.dev",
        )

    val topLevelDomains = listOf("com", "com", "com", "io", "net", "org", "dev", "co", "vn", "com.vn", "app", "tech")

    val companyRoots =
        listOf(
            "Acme", "Globex", "Initech", "Umbrella", "Stark", "Wayne", "Hooli", "Vandelay", "Lotus", "Saigon", "Mekong",
            "Hanoi", "Bamboo", "Lotte", "Sunrise", "Bluewave", "Northwind", "Contoso", "Fabrikam", "Tailspin", "Pinnacle",
            "Summit", "Evergreen", "Silverline", "Redwood", "Quantum", "Nimbus", "Orbit", "Vertex", "Apex", "Horizon", "Atlas",
            "Phoenix", "Golden Dragon", "Lighthouse", "Harbor", "Meridian", "Crescent", "Falcon", "Cobalt",
        )

    val companyIndustries =
        listOf(
            "Tech", "Labs", "Systems", "Solutions", "Logistics", "Foods", "Trading", "Media", "Consulting", "Holdings",
            "Software", "Analytics", "Capital", "Energy", "Retail", "Health", "Studio", "Networks", "Motors", "Industries",
        )

    val companySuffixes = listOf("", "", "", " Inc.", " LLC", " Ltd.", " JSC", " Co.", " GmbH", " Group")

    val jobTitles =
        listOf(
            "Software Engineer", "Senior Software Engineer", "Staff Engineer", "Engineering Manager", "Product Manager",
            "Product Designer", "UX Researcher", "Data Analyst", "Data Scientist", "Data Engineer", "DevOps Engineer",
            "QA Engineer", "Security Engineer", "Solutions Architect", "CTO", "CEO", "CFO", "COO", "Marketing Manager",
            "Growth Marketer", "Content Writer", "Sales Representative", "Account Executive", "Customer Success Manager",
            "Support Specialist", "HR Manager", "Recruiter", "Office Manager", "Accountant", "Financial Analyst",
            "Operations Manager", "Project Manager", "Business Analyst", "Legal Counsel", "Graphic Designer",
            "Mobile Developer", "Frontend Developer", "Backend Developer", "Intern", "Team Lead",
        )

    val streetNames =
        listOf(
            "Main", "Oak", "Maple", "Cedar", "Pine", "Elm", "Washington", "Lake", "Hill", "Park", "Sunset", "River", "Church",
            "Highland", "Mill", "Spring", "Market", "King", "Queen", "Victoria", "Station", "High",
        )

    val streetTypes = listOf("Street", "Avenue", "Road", "Boulevard", "Lane", "Drive", "Way", "Court", "Place")

    /** Vietnamese streets are named without a type word: "12 Lê Lợi". */
    val vietnameseStreets =
        listOf(
            "Lê Lợi", "Nguyễn Huệ", "Trần Hưng Đạo", "Hai Bà Trưng", "Lý Thường Kiệt", "Điện Biên Phủ", "Võ Văn Tần",
            "Pasteur", "Đồng Khởi", "Phan Đình Phùng", "Nguyễn Trãi", "Cách Mạng Tháng Tám", "Hoàng Diệu", "Bạch Đằng",
        )

    val lorem =
        listOf(
            "lorem", "ipsum", "dolor", "sit", "amet", "consectetur", "adipiscing", "elit", "sed", "do", "eiusmod", "tempor",
            "incididunt", "ut", "labore", "et", "dolore", "magna", "aliqua", "enim", "ad", "minim", "veniam", "quis", "nostrud",
            "exercitation", "ullamco", "laboris", "nisi", "aliquip", "ex", "ea", "commodo", "consequat", "duis", "aute",
            "irure", "in", "reprehenderit", "voluptate", "velit", "esse", "cillum", "fugiat", "nulla", "pariatur", "excepteur",
            "sint", "occaecat", "cupidatat", "non", "proident", "sunt", "culpa", "qui", "officia", "deserunt", "mollit",
            "anim", "id", "est", "laborum", "vitae", "semper", "porta", "felis", "mauris", "tellus", "integer", "vivamus",
        )

    val adjectives =
        listOf(
            "quick", "bright", "silent", "bold", "gentle", "clever", "fresh", "golden", "hidden", "lucky", "modern", "quiet",
            "rapid", "smart", "smooth", "solid", "sunny", "swift", "tiny", "vast", "wild", "classic", "cozy", "daily", "early",
            "easy", "global", "happy", "honest", "local", "magic", "simple",
        )

    val nouns =
        listOf(
            "river", "mountain", "garden", "harbor", "forest", "island", "market", "bridge", "planet", "signal", "rocket",
            "journey", "window", "season", "pixel", "anchor", "lantern", "meadow", "canvas", "compass", "village", "ocean",
            "falcon", "tiger", "lotus", "bamboo", "coffee", "kitchen", "studio", "report", "project", "update", "guide", "story",
        )

    val productAdjectives =
        listOf(
            "Ergonomic", "Rustic", "Sleek", "Intelligent", "Gorgeous", "Handcrafted", "Practical", "Refined", "Durable",
            "Lightweight", "Premium", "Compact", "Wireless", "Portable", "Organic", "Vintage", "Smart", "Deluxe",
        )

    val productMaterials =
        listOf("Wooden", "Steel", "Cotton", "Leather", "Bamboo", "Ceramic", "Granite", "Plastic", "Rubber", "Silk", "Glass", "Linen", "Aluminum")

    val products =
        listOf(
            "Chair", "Table", "Lamp", "Keyboard", "Mouse", "Backpack", "Bottle", "Mug", "Headphones", "Speaker", "Watch",
            "Wallet", "Jacket", "Shoes", "Hat", "Notebook", "Pen", "Desk", "Sofa", "Pillow", "Blanket", "Bicycle", "Helmet",
            "Charger", "Camera", "Monitor", "Teapot", "Rice Cooker", "Conical Hat", "Phone Case",
        )

    val fileExtensions = listOf("pdf", "png", "jpg", "csv", "txt", "docx", "xlsx", "json", "zip", "mp4")

    val phoneFormats =
        listOf(
            "+1 (###) ###-####", "+84 9## ### ###", "+84 3## ### ###", "09## ### ###", "+44 7### ######", "+81 90-####-####",
            "+33 6 ## ## ## ##", "+49 15# #######", "+91 9#########", "(###) ###-####",
        )

    /** `#` is a digit, `A` an upper-case letter. */
    val postalFormats = listOf("#####", "#####", "#####-####", "######", "A#A #A#", "AA# #AA", "###-####", "####")

    /** Lower-case ASCII form of a name for emails and user names: `Nguyễn` -> `nguyen`, `Müller` -> `muller`. */
    fun ascii(text: String): String = asciiCache[text] ?: fold(text)

    private fun fold(text: String): String =
        Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }

    // Folding normalizes Unicode, which is slow next to everything else per row; names are few, so fold them once.
    private val asciiCache: Map<String, String> =
        (namePools.flatMap { it.first + it.last } + companyRoots).associateWith { fold(it) }
}
