package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.userservice.model.Consultant;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.List;
import java.util.Random;

@JBossLog
@ApplicationScoped
public class BirthdayNotificationJob {

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    @Scheduled(cron = "0 0 7 * * ?")
    public void sendBirthdayNotifications() {
        List<Consultant> consultants = em.createNativeQuery("select * from twservices.consultant c " +
                "         where " +
                "             c.status like 'ACTIVE' and " +
                "             c.type not like 'EXTERNAL' and " +
                "             MONTH(birthday) = MONTH(CURDATE()) and " +
                "             DAY(birthday) = DAY(CURDATE());", Consultant.class).getResultList();
        for (Consultant consultant : consultants) {
            slackService.sendMessage(consultant.getSlackusername(), getRandomWish(consultant.getFirstname()+" "+consultant.getLastname(), consultant.getGender()));
            slackService.sendMessage("#general", getRandomWish(consultant.getFirstname()+" "+consultant.getLastname(), consultant.getGender()));
        }
    }

    private static final String[] WISHES = {
            ":flag-dk::tada::cake: [Name], congrats on leveling up in the game of life! :tada: P.S. Is that cake I see at your desk? :flag-dk:",
            ":flag-dk::balloon: [Name], another spin around the sun and you're just glowing! :sunglasses: Hope your day is all about less :email: and more :cake:! :flag-dk:",
            ":flag-dk::tada: Happy [Name]'s Day! :partyparrot: May your cake be bug-free, unlike some of our code. :flag-dk:",
            ":flag-dk::cake: Cake calories don't count today, right [Name]? Dive in! :fork_and_knife: :flag-dk:",
            ":flag-dk::cake: [Name]'s epic escape-from-the-womb anniversary is today! Shine on, birthday star! :star2: :flag-dk:",
            ":flag-dk::champagne: Age is just a number, but [Name]'s cake slices are limitless! Here's to being forever young at heart! :heartpulse: :flag-dk:",
            ":flag-dk::gift: [Name], every year brings you closer to those sweet senior discounts! Party like it’s... your birthday! :tada: :flag-dk:",
            ":flag-dk::tada: A legend named [Name] was born on this day! Best. Day. Ever. :flag-dk:",
            ":flag-dk::clinking_glasses: To [Name]: The hero of 'I-hope-there's-leftover-office-cake' Day! Cheers! :flag-dk:",
            ":flag-dk::cake: A big Birth-YAY shoutout to [Name]! Let today be as delightful as finding a hidden :doughnut: in the office! :flag-dk:",
            ":flag-dk::tada: [Name], do the Danish vibes give you an extra birthday boost? Enjoy the ride! :flag-dk:",
            ":flag-dk::balloon: On this day, [Name], you've earned another stripe on your age badge! Flaunt it! :flag-dk:",
            ":flag-dk::tada: It's [Name]'s day, and the Danish flags are flying high! Keep the spirits higher! :flag-dk:",
            ":flag-dk::cake: [Name], may your day be sprinkled with joy and Danish magic! :flag-dk:",
            ":flag-dk::cake: Legend has it, every time a Danish flag is waved, [Name] gets an extra slice of cake! :flag-dk:",
            ":flag-dk::champagne: [Name], another year wiser, another year Danish-er! Cheers to many more! :flag-dk:",
            ":flag-dk::gift: Birthdays are nature's way of telling us to eat more cake, right [Name]? Dig in! :flag-dk:",
            ":flag-dk::tada: Today, [Name], you're the star, and Denmark's cheering for you! :flag-dk:",
            ":flag-dk::clinking_glasses: [Name], clink your glasses to another year of fabulous you! With a Danish touch! :flag-dk:",
            ":flag-dk::cake: [Name], blow the candles, make a wish, and let the Danish vibes flow! :flag-dk:",
            ":alarm_clock: Rise and shine, [Name]! It's your day and we won't let you forget it! :birthday:",
            ":flag-dk::dancer: [Name], the disco ball's out and it’s time to groove! Happy B-day! :notes:",
            ":flag-dk::sparkles: If age is an art, [Name], you're a Picasso in the making! :paintbrush:",
            ":flag-dk::rocket: Another trip around the sun, [Name]? Fasten your seatbelt! :star2:",
            ":flag-dk::sunglasses: [Name], you aren’t old, you’re vintage! Cheers to timeless you! :wine_glass:",
            ":flag-dk::gift: Unwrapping the best gift today, [Name] – it's you! :tada:",
            ":flag-dk::balloon: Float like the balloons today, [Name]! It's your time in the sky! :cloud:",
            ":flag-dk::cake: Calories, beware! [Name]'s on the loose and ready to feast! :fork_and_knife:",
            ":flag-dk::clinking_glasses: Here's to [Name], who's just a year more fabulous! :sparkles:",
            ":flag-dk::microphone: [Name], drop the age and embrace the sage! :book:",
            ":flag-dk::zap: Another year, another spark for [Name]! Electrify the day! :electric_plug:",
            ":flag-dk::doughnut: Sprinkle some joy, glaze some memories, [Name] – it's your donut day! :birthday:",
            ":flag-dk::confetti_ball: [Name]'s official day of royalty is here! All hail! :crown:",
            ":flag-dk::muscle: Another year stronger, [Name]! Flex those birthday muscles! :tada:",
            ":flag-dk::mag_right: Searching for [Name]'s fountain of youth... still loading... :hourglass_flowing_sand:",
            ":flag-dk::boom: It’s not fireworks; it’s just [Name]'s radiant birthday vibes! :fire:",
            ":flag-dk::tophat: Age with elegance and a dash of mischief, [Name]! :wink:",
            ":flag-dk::robot_face: [Name] is not aging, just updating to a newer version! :gear:",
            ":flag-dk::sweat_smile: Older? Sure. Wiser? Debatable. Happier? Absolutely! Cheers, [Name]!",
            ":flag-dk::alarm_clock: [Name], if birthdays had snooze buttons! But hey, rise and celebrate! :birthday:",
            ":flag-dk::hatching_chick: A little birdie told me it's [Name]'s birthday! Tweet Tweet Hooray! :bird:",
            ":flag-dk::video_game: Level up! [Name], your game just got an update! :trophy:",
            ":flag-dk::musical_note: Let the birthday beats drop, [Name]! Dance like nobody's pinging you! :headphones:",
            ":flag-dk::balloon: Pop goes the birthday! [Name], you're the party everyone wants to join! :confetti_ball:",
            ":flag-dk::sunglasses: Age is all about perspective, [Name]. From here, you're looking great! :camera_with_flash:",
            ":flag-dk::memo: Note to everyone: Clear your afternoon. It’s [Name]'s cake-eating time! :fork_and_knife:",
            ":flag-dk::rainbow: Somewhere over the birthday rainbow, [Name] is celebrating! :unicorn_face:",
            ":flag-dk::roller_coaster: Another wild trip around the sun! Hold tight, [Name]! :earth_americas:",
            ":flag-dk::clinking_glasses: Cheers to [Name], the reason we're all looking forward to the cake today! :beer:",
            ":flag-dk::book: [Name], your life’s story just got another exciting chapter! :black_nib:",
            ":flag-dk::rocket: 3... 2... 1... Blast off to [Name]'s next adventure-filled year! :stars:",
            ":flag-dk::gift_heart: Unwrapping the day with joy, it's [Name]'s special moment! :ribbon:",
            ":flag-dk::spiral_calendar_pad: Mark your calendars, folks! Today, [Name] gets the spotlight! :flashlight:",
            ":flag-dk::mage: May [Name]'s day be spellbinding and magical! :crystal_ball:",
            ":flag-dk::trophy: Celebrating [Name], the champion of birthdays! Keep winning! :medal:",
            ":flag-dk::beers: Age gets better with beer! [Name], pour yourself some birthday cheer! :tumbler_glass:",
            ":flag-dk::sparkler: Light up the day, [Name]! It’s your time to shine! :sun_with_face:",
            ":flag-dk::pizza: Like a fine pizza, [Name] only gets better with time! Slice slice baby! :knife_fork_plate:",
            ":flag-dk::zany_face: Wilder, wackier, and wonderfully older! Dive into the fun, [Name]!"
    };

    public static String getRandomWish(String name, String gender) {
        Random rand = new Random();
        int index = rand.nextInt(WISHES.length);
        return WISHES[index].replace("[Name]", name);
    }


}
