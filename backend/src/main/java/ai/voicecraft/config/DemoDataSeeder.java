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
    private final ai.voicecraft.voice.VoiceLibrarySync librarySync;
    private String demoUserId;

    public DemoDataSeeder(VoiceRepository voices, ProjectRepository projects, AccountRepository accounts, UserRepository users,
                          VoiceFavoriteRepository favorites, PasswordEncoder encoder, AppProperties props,
                          ai.voicecraft.voice.VoiceLibrarySync librarySync) {
        this.librarySync = librarySync;
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
        librarySync.sync();
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
        favorites.save(new VoiceFavorite(u.id, "voice_warm_velvet"));
        favorites.save(new VoiceFavorite(u.id, "voice_warm_narrator"));
        seedProjects();
    }

    private void seedProjects() {
        project("Podcast „Miasto po zmroku” – odcinek 12", "voice_warm_narrator", 1, List.of(
                "Witajcie w dwunastym odcinku podcastu „Miasto po zmroku”. Dziś zabiorę was tam, gdzie miasto cichnie — choć nigdy nie zasypia do końca.",
                "Kiedy ostatnie tramwaje zjeżdżają do zajezdni, ulice zmieniają charakter. Zostają piekarze, taksówkarze, pielęgniarki z nocnej zmiany i ci, którzy po prostu nie mogą zasnąć.",
                "Rozmawiałem z kilkorgiem z nich. Ich historie są zaskakująco podobne: noc daje im coś, czego dzień nie potrafi — spokój i poczucie, że miasto należy na chwilę tylko do nich.",
                "Zanim jednak oddam im głos, krótka uwaga. Wszystkie rozmowy nagrałem między pierwszą a czwartą nad ranem, więc usłyszycie w tle prawdziwe nocne miasto."));
        project("E-learning: Bezpieczeństwo danych – moduł 1", "voice_warm_velvet", 3, List.of(
                "Moduł pierwszy: dlaczego bezpieczeństwo danych dotyczy każdego z nas.",
                "Każdego dnia przetwarzamy informacje o klientach, współpracownikach i projektach. Jedna nieprzemyślana wiadomość może sprawić, że trafią one w niepowołane ręce.",
                "W tym module nauczysz się rozpoznawać najczęstsze zagrożenia: wiadomości phishingowe, słabe hasła i niezabezpieczone urządzenia.",
                "Na końcu czeka na ciebie krótki quiz. Zaliczenie go jest warunkiem przejścia do kolejnej części szkolenia."));
        project("Spot radiowy – Kawiarnia Ziarno", "voice_young_influencer", 6, List.of(
                "Poniedziałek rano? Nie musi być szary!",
                "Kawiarnia Ziarno zaprasza na kawę z ziaren palonych na miejscu i świeże drożdżówki prosto z pieca.",
                "Ulica Długa 14. Otwarte codziennie od siódmej. Ziarno — dobry dzień zaczyna się tutaj."));
        project("Audiobook „Latarnik z Helu” – rozdział 1", "voice_kind_grandma", 12, List.of(
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
