package ai.voicecraft.config;

import ai.voicecraft.account.AccountRepository;
import ai.voicecraft.account.AccountService;
import ai.voicecraft.account.SubscriptionPlan;
import ai.voicecraft.auth.DemoAccount;
import ai.voicecraft.user.User;
import ai.voicecraft.user.UserRepository;
import ai.voicecraft.voice.VoiceFavorite;
import ai.voicecraft.voice.VoiceFavoriteRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import ai.voicecraft.project.Project;
import ai.voicecraft.project.ProjectRepository;
import ai.voicecraft.project.Segment;
import ai.voicecraft.voice.Voice;
import ai.voicecraft.voice.VoiceKind;
import ai.voicecraft.voice.VoiceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Biblioteka głosów (zawsze) oraz — przy SEED_DEMO_DATA=true — konto demonstracyjne z przykładowymi
 * projektami (bez wygenerowanego audio). W profilu prod konto demo nie jest tworzone.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {
    private final VoiceRepository voices;
    private final ProjectRepository projects;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final VoiceFavoriteRepository favorites;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private String demoUserId;

    public DemoDataSeeder(VoiceRepository voices, ProjectRepository projects, AccountRepository accounts, UserRepository users,
                          VoiceFavoriteRepository favorites, PasswordEncoder encoder, AppProperties props) {
        this.voices = voices;
        this.projects = projects;
        this.accounts = accounts;
        this.users = users;
        this.favorites = favorites;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (voices.count() == 0) seedVoices();
        if (!props.seedDemoData() || users.existsByEmailIgnoreCase(DemoAccount.EMAIL)) return;
        var u = new User();
        u.id = java.util.UUID.randomUUID().toString();
        u.email = DemoAccount.EMAIL;
        u.fullName = "Marta Nowak";
        u.passwordHash = encoder.encode(DemoAccount.PASSWORD);
        u.emailVerifiedAt = java.time.Instant.now();
        u.termsAcceptedAt = u.emailVerifiedAt;
        u.termsVersion = ai.voicecraft.auth.LegalDocuments.TERMS_VERSION;
        users.save(u);
        var a = AccountService.create(u, SubscriptionPlan.PRO);
        a.company = "Studio Głos";
        a.role = "Producentka audio";
        accounts.save(a);
        demoUserId = u.id;
        favorites.save(new VoiceFavorite(u.id, "pl-anna"));
        favorites.save(new VoiceFavorite(u.id, "pl-tomasz"));
        seedProjects();
    }

    private static final String PL = "Dzień dobry. Nazywam się %s i chętnie przeczytam Twój tekst — spokojnie, wyraźnie i z odpowiednią intonacją.";

    private void seedVoices() {
        voice("pl-anna", "Anna", "female", 32, "pl", List.of("natural", "professional"), List.of("ciepły", "klarowny", "średni"),
                "Ciepły, klarowny głos kobiecy. Uniwersalny wybór do e-learningu, prezentacji i komunikatów firmowych.",
                "Polish female voice, early thirties, warm and clear mid-range timbre, natural conversational delivery", true);
        voice("pl-tomasz", "Tomasz", "male", 38, "pl", List.of("narrative", "calm"), List.of("niski", "aksamitny", "spokojny"),
                "Niski, aksamitny głos lektorski. Stworzony do filmów dokumentalnych i audiobooków.",
                "Polish male voice, late thirties, low velvety timbre, calm measured documentary narration", true);
        voice("pl-julia", "Julia", "female", 24, "pl", List.of("energetic", "advertising"), List.of("jasny", "dynamiczny", "uśmiechnięty"),
                "Jasny, dynamiczny głos z uśmiechem. Sprawdza się w reklamach, social media i materiałach produktowych.",
                "Young Polish female voice, mid twenties, bright and energetic, smiling upbeat commercial delivery", false);
        voice("pl-marek", "Marek", "male", 45, "pl", List.of("professional"), List.of("rzeczowy", "pewny", "średnio niski"),
                "Rzeczowy, pewny siebie głos. Do komunikacji korporacyjnej, raportów i serwisów informacyjnych.",
                "Polish male voice, mid forties, confident and authoritative, crisp professional news-style delivery", false);
        voice("pl-zofia", "Zofia", "female", 58, "pl", List.of("narrative", "calm"), List.of("dojrzały", "ciepły", "opiekuńczy"),
                "Dojrzały, ciepły głos opowiadaczki. Idealny do bajek, wspomnień i literatury.",
                "Mature Polish female voice, late fifties, warm and caring storyteller, gentle unhurried pacing", false);
        voice("pl-kacper", "Kacper", "male", 27, "pl", List.of("natural", "energetic"), List.of("swobodny", "młodzieńczy"),
                "Swobodny, naturalny głos. Do podcastów, vlogów i treści, które mają brzmieć jak rozmowa.",
                "Young Polish male voice, late twenties, casual relaxed conversational tone, podcast style", false);
        voice("pl-ewa", "Ewa", "female", 40, "pl", List.of("calm"), List.of("łagodny", "kojący", "miękki"),
                "Łagodny, kojący głos. Do medytacji, materiałów zdrowotnych i spokojnych instrukcji.",
                "Polish female voice, around forty, soft soothing breathy timbre, slow calm meditative delivery", false);
        voice("pl-adam", "Adam", "male", 35, "pl", List.of("advertising", "professional"), List.of("głęboki", "wyrazisty"),
                "Głęboki, wyrazisty głos o dużej sile. Do zwiastunów, spotów radiowych i intro.",
                "Polish male voice, mid thirties, deep resonant and punchy, dramatic trailer and radio spot delivery", false);
        voice("en-claire", "Claire", "female", 34, "en", List.of("professional", "natural"), List.of("brytyjski", "klarowny"),
                "Klarowny głos z akcentem brytyjskim. Do szkoleń i prezentacji anglojęzycznych.",
                "British English female voice, mid thirties, clear refined received pronunciation, professional", false);
        voice("en-james", "James", "male", 42, "en", List.of("narrative"), List.of("amerykański", "niski", "ciepły"),
                "Niski, ciepły głos amerykański. Narracja, audiobooki, dokumenty.",
                "American English male voice, early forties, low warm baritone, engaging audiobook narration", false);
        voice("en-maya", "Maya", "female", 26, "en", List.of("energetic", "advertising"), List.of("amerykański", "radosny"),
                "Radosny, energiczny głos amerykański do reklam i treści lifestyle.",
                "American English female voice, mid twenties, cheerful energetic and friendly, commercial read", false);
        voice("de-lukas", "Lukas", "male", 36, "de", List.of("professional"), List.of("rzeczowy", "neutralny"),
                "Neutralny, rzeczowy głos niemiecki do materiałów technicznych i korporacyjnych.",
                "German male voice, mid thirties, neutral precise and professional corporate delivery", false);
        voice("fr-camille", "Camille", "female", 30, "fr", List.of("natural", "calm"), List.of("miękki", "elegancki"),
                "Miękki, elegancki głos francuski.",
                "French female voice, around thirty, soft elegant and calm, natural intimate delivery", false);
        voice("es-diego", "Diego", "male", 29, "es", List.of("energetic"), List.of("żywy", "ciepły"),
                "Żywy, ciepły głos hiszpański do treści promocyjnych i wideo.",
                "Spanish male voice, late twenties, lively warm and energetic, upbeat video voiceover", false);
        voice("it-giulia", "Giulia", "female", 35, "it", List.of("narrative"), List.of("melodyjny", "ciepły"),
                "Melodyjny głos włoski, świetny do opowieści i przewodników.",
                "Italian female voice, mid thirties, melodic warm storytelling voice, expressive", false);
        voice("pt-ines", "Inês", "female", 31, "pt", List.of("natural", "professional"), List.of("klarowny", "spokojny"),
                "Klarowny, spokojny głos portugalski do komunikatów i materiałów informacyjnych.",
                "European Portuguese female voice, early thirties, clear calm and natural informative delivery", false);
    }

    private void voice(String id, String name, String gender, int age, String lang, List<String> categories,
                       List<String> traits, String description, String prompt, boolean favorite) {
        var v = new Voice();
        v.id = id;
        v.name = name;
        v.kind = VoiceKind.LIBRARY;
        v.gender = gender;
        v.age = age;
        v.languages = List.of(lang);
        v.categories = categories;
        v.traits = traits;
        v.description = description;
        v.enginePrompt = prompt;
        v.sampleText = switch (lang) {
            case "pl" -> PL.formatted(name);
            case "en" -> "Hello, I'm " + name + ". Give me your script and I'll bring it to life — clearly, naturally and with the right tone.";
            case "de" -> "Guten Tag, ich bin " + name + ". Ich lese Ihren Text klar, natürlich und mit dem richtigen Ton.";
            case "fr" -> "Bonjour, je m'appelle " + name + ". Je lirai votre texte avec clarté, naturel et le ton juste.";
            case "es" -> "Hola, soy " + name + ". Leeré tu texto con claridad, naturalidad y el tono adecuado.";
            case "it" -> "Ciao, sono " + name + ". Leggerò il tuo testo con chiarezza, naturalezza e il tono giusto.";
            case "pt" -> "Olá, chamo-me " + name + ". Vou ler o seu texto com clareza, naturalidade e o tom certo.";
            default -> "Hello, I'm " + name + ".";
        };
        voices.save(v);
    }

    private void seedProjects() {
        project("Podcast „Miasto po zmroku” – odcinek 12", "pl-tomasz", 1, List.of(
                "Witajcie w dwunastym odcinku podcastu „Miasto po zmroku”. Dziś zabiorę was tam, gdzie miasto cichnie — choć nigdy nie zasypia do końca.",
                "Kiedy ostatnie tramwaje zjeżdżają do zajezdni, ulice zmieniają charakter. Zostają piekarze, taksówkarze, pielęgniarki z nocnej zmiany i ci, którzy po prostu nie mogą zasnąć.",
                "Rozmawiałem z kilkorgiem z nich. Ich historie są zaskakująco podobne: noc daje im coś, czego dzień nie potrafi — spokój i poczucie, że miasto należy na chwilę tylko do nich.",
                "Zanim jednak oddam im głos, krótka uwaga. Wszystkie rozmowy nagrałem między pierwszą a czwartą nad ranem, więc usłyszycie w tle prawdziwe nocne miasto."));
        project("E-learning: Bezpieczeństwo danych – moduł 1", "pl-anna", 3, List.of(
                "Moduł pierwszy: dlaczego bezpieczeństwo danych dotyczy każdego z nas.",
                "Każdego dnia przetwarzamy informacje o klientach, współpracownikach i projektach. Jedna nieprzemyślana wiadomość może sprawić, że trafią one w niepowołane ręce.",
                "W tym module nauczysz się rozpoznawać najczęstsze zagrożenia: wiadomości phishingowe, słabe hasła i niezabezpieczone urządzenia.",
                "Na końcu czeka na ciebie krótki quiz. Zaliczenie go jest warunkiem przejścia do kolejnej części szkolenia."));
        project("Spot radiowy – Kawiarnia Ziarno", "pl-julia", 6, List.of(
                "Poniedziałek rano? Nie musi być szary!",
                "Kawiarnia Ziarno zaprasza na kawę z ziaren palonych na miejscu i świeże drożdżówki prosto z pieca.",
                "Ulica Długa 14. Otwarte codziennie od siódmej. Ziarno — dobry dzień zaczyna się tutaj."));
        project("Audiobook „Latarnik z Helu” – rozdział 1", "pl-zofia", 12, List.of(
                "Rozdział pierwszy. Wiatr od morza.",
                "Stary Ignacy wstawał zawsze przed świtem. Nie potrzebował budzika — budził go szum fal, który o tej porze zmieniał ton, jakby morze też przeciągało się po długiej nocy.",
                "Tego dnia jednak coś było inaczej. Na progu latarni leżała koperta, wilgotna od mgły, zaadresowana starannym, nieznanym mu pismem."));
    }

    private void project(String name, String voiceId, int daysAgo, List<String> paragraphs) {
        var p = new Project();
        p.id = UUID.randomUUID().toString();
        p.ownerId = demoUserId;
        p.name = name;
        p.createdAt = Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(daysAgo * 3L));
        p.updatedAt = p.createdAt.plus(Duration.ofHours(2));
        p.settings.voiceId = voiceId;
        int i = 0;
        for (String t : paragraphs) {
            var s = new Segment();
            s.id = UUID.randomUUID().toString();
            s.project = p;
            s.position = i++;
            s.text = t;
            p.segments.add(s);
        }
        projects.save(p);
    }
}
