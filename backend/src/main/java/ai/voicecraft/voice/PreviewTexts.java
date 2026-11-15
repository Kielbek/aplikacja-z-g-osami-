package ai.voicecraft.voice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Krótkie zdania próbek odsłuchowych w 30 językach VoxCPM2. Wszystkie głosy mówią tym samym zdaniem,
 * więc słychać różnicę barwy, a nie treści. Tłumaczenia na rzadsze języki warto zweryfikować z native speakerem.
 */
public final class PreviewTexts {
    private PreviewTexts() {}

    public static final Map<String, String> TEXTS = new LinkedHashMap<>();

    static {
        TEXTS.put("pl", "Cześć! Tak brzmi mój głos. Miło mi cię poznać.");
        TEXTS.put("en", "Hello! This is how my voice sounds. Nice to meet you.");
        TEXTS.put("de", "Hallo! So klingt meine Stimme. Schön, dich kennenzulernen.");
        TEXTS.put("fr", "Bonjour ! Voici à quoi ressemble ma voix. Ravi de vous rencontrer.");
        TEXTS.put("es", "¡Hola! Así suena mi voz. Encantado de conocerte.");
        TEXTS.put("it", "Ciao! Questa è la mia voce. Piacere di conoscerti.");
        TEXTS.put("pt", "Olá! É assim que soa a minha voz. Muito prazer.");
        TEXTS.put("nl", "Hallo! Zo klinkt mijn stem. Leuk je te ontmoeten.");
        TEXTS.put("da", "Hej! Sådan lyder min stemme. Rart at møde dig.");
        TEXTS.put("sv", "Hej! Så här låter min röst. Trevligt att träffas.");
        TEXTS.put("no", "Hei! Slik høres stemmen min ut. Hyggelig å møte deg.");
        TEXTS.put("fi", "Hei! Tältä ääneni kuulostaa. Hauska tavata.");
        TEXTS.put("ru", "Привет! Так звучит мой голос. Приятно познакомиться.");
        TEXTS.put("el", "Γεια σου! Έτσι ακούγεται η φωνή μου. Χάρηκα που σε γνώρισα.");
        TEXTS.put("tr", "Merhaba! Sesim böyle duyuluyor. Tanıştığımıza memnun oldum.");
        TEXTS.put("ar", "مرحبًا! هكذا يبدو صوتي. سعيد بلقائك.");
        TEXTS.put("he", "שלום! ככה נשמע הקול שלי. נעים להכיר.");
        TEXTS.put("hi", "नमस्ते! मेरी आवाज़ ऐसी सुनाई देती है। आपसे मिलकर खुशी हुई।");
        TEXTS.put("id", "Halo! Beginilah suara saya. Senang bertemu dengan Anda.");
        TEXTS.put("ms", "Helo! Beginilah bunyi suara saya. Selamat berkenalan.");
        TEXTS.put("tl", "Kumusta! Ganito ang tunog ng aking boses. Ikinagagalak kitang makilala.");
        TEXTS.put("vi", "Xin chào! Đây là giọng nói của tôi. Rất vui được gặp bạn.");
        TEXTS.put("th", "สวัสดี! นี่คือเสียงของฉัน ยินดีที่ได้รู้จัก");
        TEXTS.put("lo", "ສະບາຍດີ! ນີ້ແມ່ນສຽງຂອງຂ້ອຍ. ຍິນດີທີ່ໄດ້ຮູ້ຈັກ.");
        TEXTS.put("km", "សួស្តី! នេះជាសំឡេងរបស់ខ្ញុំ។ រីករាយដែលបានស្គាល់អ្នក។");
        TEXTS.put("my", "မင်္ဂလာပါ! ဒါက ကျွန်တော့်အသံပါ။ တွေ့ရတာ ဝမ်းသာပါတယ်။");
        TEXTS.put("zh", "你好！这就是我的声音。很高兴认识你。");
        TEXTS.put("ja", "こんにちは！これが私の声です。よろしくお願いします。");
        TEXTS.put("ko", "안녕하세요! 이것이 제 목소리입니다. 만나서 반갑습니다.");
        TEXTS.put("sw", "Habari! Hivi ndivyo sauti yangu inavyosikika. Nimefurahi kukutana nawe.");
    }

    public static boolean supported(String lang) {
        return lang != null && TEXTS.containsKey(lang);
    }
}
