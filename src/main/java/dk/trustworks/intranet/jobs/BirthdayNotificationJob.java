package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.userservice.model.Employee;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Random;

@JBossLog
@ApplicationScoped
public class BirthdayNotificationJob {

    @Inject
    EntityManager em;

    @Inject
    SlackService slackService;

    /**
     * This method sends birthday notifications to employees with birthday today.
     * It runs every day at 5:00 AM.
     */
    @Scheduled(cron = "0 0 5 * * ?")
    public void sendBirthdayNotifications() {
        log.info("Checking for employees with birthday today");
        @SuppressWarnings("unchecked")
        List<Employee> employees = em.createNativeQuery("select * from consultant c " +
                "         where " +
                "             c.status like 'ACTIVE' and " +
                "             c.type not in ('EXTERNAL') and " +
                "             MONTH(birthday) = MONTH(CURDATE()) and " +
                "             DAY(birthday) = DAY(CURDATE())", Employee.class)
                .getResultList();
        log.info("Found " + employees.size() + " employees with birthday today");
        for (Employee employee : employees) {
            log.info("Sending birthday notification: " + employee.getFirstname() + " " + employee.getLastname());
            slackService.sendMessage("#general", getRandomWish(employee.getFirstname()+" "+ employee.getLastname(), employee.getGender()));
        }
    }

    private static final String[] WISHES = {
            /* NICE AND FRIENDLY */
            /*
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
            ":flag-dk::zany_face: Wilder, wackier, and wonderfully older! Dive into the fun, [Name]!"*/
            ":flag-dk::alarm_clock: [Name], another year and STILL not wiser? Let's hope those candles bring some enlightenment! :birthday:",
            ":flag-dk::hatching_chick: [Name], you're not getting older, just more... seasoned. Peppered with wisdom, perhaps? :bird:",
            ":flag-dk::video_game: Leveled up in age, [Name]? But are your gaming skills leveling up too? :trophy:",
            ":flag-dk::balloon: [Name], balloons for your birthday? Or are they for your ever-expanding age ego? :confetti_ball:",
            ":flag-dk::sunglasses: [Name], you say age is just a number, but it's starting to sound like a phone number, isn't it? :camera_with_flash:",
            ":flag-dk::memo: Older, bolder, and... just plain older. But who's counting? Oh right, we are. Happy Birthday, [Name]! :fork_and_knife:",
            ":flag-dk::roller_coaster: [Name], getting old is like a roller coaster. Lots of screams, some fun, and a few unexpected drops! Enjoy the ride! :earth_americas:",
            ":flag-dk::clinking_glasses: A toast to [Name]! Like wine, aging but... also, like wine, possibly better left in the cellar? :beer:",
            ":flag-dk::book: Another year, another wrinkle, [Name]. Think of them as life's bookmarks! :pen_fountain:",
            ":flag-dk::rocket: [Name], your age's reaching new heights. Maybe it’s time it came back to Earth? :stars:",
            ":flag-dk::gift_heart: [Name], you're a vintage model now. But remember, classic doesn't mean obsolete... yet! :ribbon:",
            ":flag-dk::spiral_calendar_pad: [Name], on your special day, remember: age is just a high score in the game of life. And you're winning! :flashlight:",
            ":flag-dk::trophy: [Name], here's to you, the MVP of making gray hair look... intentional! Go champ! :medal:",
            ":flag-dk::beers: [Name], remember, beers may get stale with age, but you? You're... fermenting? Cheers! :tumbler_glass:",
            ":flag-dk::sparkler: [Name], like a rare coin, you're gaining value. Too bad it's in laugh lines! Celebrate! :sun_with_face:",
            ":flag-dk::pizza: [Name], you're a slice above the rest. A slightly older, crustier slice, but still our favorite! :knife_fork_plate:",
            ":flag-dk::zany_face: [Name], you're not older – you're just a classic edition with added... character! Enjoy your day!",
            ":flag-dk::alarm_clock: [Name], don't you love birthdays? Another year closer to looking like your passport photo. :birthday:",
            ":flag-dk::hatching_chick: Heard [Name]'s aging like a fine wine. That explains the sour notes! :bird:",
            ":flag-dk::video_game: Ding! [Name] leveled up in age! Still waiting for that charisma boost though... :trophy:",
            ":flag-dk::musical_note: [Name], if age was a song, you'd be a lengthy opera by now! Care for a solo? :headphones:",
            ":flag-dk::balloon: [Name], those aren't birthday balloons. They're your age’s SOS signals! :confetti_ball:",
            ":flag-dk::sunglasses: [Name], with age comes wisdom. Or in your case, more audacity to wear those clothes! :camera_with_flash:",
            ":flag-dk::memo: Remember when [Name] was young and spry? Me neither. Happy Birthday anyway! :fork_and_knife:",
            ":flag-dk::rainbow: [Name], you’re the pot of... aged gold at the end of the rainbow. Still shiny, right? :unicorn_face:",
            ":flag-dk::roller_coaster: [Name]'s age is like a roller coaster: surprising, a bit scary, and probably should come with height restrictions. :earth_americas:",
            ":flag-dk::clinking_glasses: Raising a glass to [Name], the poster child for 'Aged. Not Matured.' Cheers! :beer:",
            ":flag-dk::book: [Name]'s life is a book. A fantasy novel where age pretends to be just a number! :pen_fountain:",
            ":flag-dk::rocket: [Name], your age is shooting for the stars! Hope the memory's not too spaced out yet! :stars:",
            ":flag-dk::gift_heart: [Name], like old cheese, you’ve got that... distinct aroma of maturity! Relish it! :ribbon:",
            ":flag-dk::spiral_calendar_pad: Reminder: [Name]'s annual ‘Pretend I'm Not Aging’ fest! Join the delusion! :flashlight:",
            ":flag-dk::mage: [Name], may your day be as unforgettable as your... last attempt to karaoke. Pure magic! :crystal_ball:",
            ":flag-dk::trophy: [Name], older, bolder, and still not the holder of any real adult skills. Keep rocking! :medal:",
            ":flag-dk::beers: [Name], the secret to looking young is low lighting and good beer. Guzzle up! :tumbler_glass:",
            ":flag-dk::sparkler: [Name], they say age is illuminating. That’s just the candles setting your cake on fire! :sun_with_face:",
            ":flag-dk::pizza: [Name], like cold pizza on a hungover morning, you're... surprisingly still good! Cheers to another year! :knife_fork_plate:",
            ":flag-dk::zany_face: [Name], aging like a banana. Still sweet, just a bit more... spotted!"
    };

    public static String getRandomWish(String name, String gender) {
        Random rand = new Random();
        int index = rand.nextInt(WISHES.length);
        return WISHES[index].replace("[Name]", name);
    }


}

/*
Absolutely, here's another 20 fun Slack greetings with the `:flag-dk:` Slack shortcode:

```java
    private static final String[] WISHES = {
        // ... (existing messages)

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
        ":flag-dk::book: [Name], your life’s story just got another exciting chapter! :pen_fountain:",
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
```

Remember to integrate this into the existing WISHES array. The greetings use a mix of humor and common Slack icons for a fun birthday experience.
 */

/*
Of course, playful teasing can make the greetings even more fun and memorable. Here's a revised list that carries a light-hearted teasing tone:

```java
    private static final String[] WISHES = {
        // ... (existing messages)

        ":flag-dk::alarm_clock: [Name], another year closer to using senior citizen discounts! No need for the snooze button today! :birthday:",
        ":flag-dk::hatching_chick: A little birdie said [Name]'s getting older? Can't tell by the dance moves! :bird:",
        ":flag-dk::video_game: [Name], you’ve leveled up in age! Maybe now you'll level up in wisdom too? :trophy:",
        ":flag-dk::musical_note: [Name], aging like a fine...cheese? Dance the stink away! :headphones:",
        ":flag-dk::balloon: [Name]'s age might be rising, but thankfully, so are our cake portions! :confetti_ball:",
        ":flag-dk::sunglasses: [Name], adding another wrinkle to the story? At least it’s in HD! :camera_with_flash:",
        ":flag-dk::memo: [Name], another year older and... another year of stealing office cake! :fork_and_knife:",
        ":flag-dk::rainbow: [Name], chasing youth like we chase rainbows! Keep running! :unicorn_face:",
        ":flag-dk::roller_coaster: [Name], that age rollercoaster is going up! Hold on for another wild year! :earth_americas:",
        ":flag-dk::clinking_glasses: A toast to [Name], who makes gray hairs look fashionable! :beer:",
        ":flag-dk::book: [Name]'s life – another year, another... wrinkle in the plot? :pen_fountain:",
        ":flag-dk::rocket: Ready for liftoff, [Name]? Your age is now in another stratosphere! :stars:",
        ":flag-dk::gift_heart: [Name], you're the gift that keeps on aging! Keep unwrapping! :ribbon:",
        ":flag-dk::spiral_calendar_pad: Calendar Alert: [Name]'s annual “Feel Young” day! Get in the spirit! :flashlight:",
        ":flag-dk::mage: [Name], may your day be as enchanting as your... emerging gray strands! :crystal_ball:",
        ":flag-dk::trophy: Celebrating [Name], the undisputed champion of 'getting older gracefully'... or at least trying to! :medal:",
        ":flag-dk::beers: [Name], they say age gets better with beer. Drink up and blur those lines! :tumbler_glass:",
        ":flag-dk::sparkler: [Name], setting the world on fire with every new candle! Burn bright! :sun_with_face:",
        ":flag-dk::pizza: [Name], aging is like pizza: sometimes cheesy, but always delightful! Dig in! :knife_fork_plate:",
        ":flag-dk::zany_face: [Name], another year zanier? At least you're consistent! Dive into the madness!"
    };
```

The greetings playfully nod to common themes of aging but keep the vibe fun and light-hearted.
 */

/*
    private static final String[] WISHES = {
        // ... (existing messages)

        ":flag-dk::alarm_clock: [Name], another year and STILL not wiser? Let's hope those candles bring some enlightenment! :birthday:",
        ":flag-dk::hatching_chick: [Name], you're not getting older, just more... seasoned. Peppered with wisdom, perhaps? :bird:",
        ":flag-dk::video_game: Leveled up in age, [Name]? But are your gaming skills leveling up too? :trophy:",
        ":flag-dk::balloon: [Name], balloons for your birthday? Or are they for your ever-expanding age ego? :confetti_ball:",
        ":flag-dk::sunglasses: [Name], you say age is just a number, but it's starting to sound like a phone number, isn't it? :camera_with_flash:",
        ":flag-dk::memo: Older, bolder, and... just plain older. But who's counting? Oh right, we are. Happy Birthday, [Name]! :fork_and_knife:",
        ":flag-dk::roller_coaster: [Name], getting old is like a roller coaster. Lots of screams, some fun, and a few unexpected drops! Enjoy the ride! :earth_americas:",
        ":flag-dk::clinking_glasses: A toast to [Name]! Like wine, aging but... also, like wine, possibly better left in the cellar? :beer:",
        ":flag-dk::book: Another year, another wrinkle, [Name]. Think of them as life's bookmarks! :pen_fountain:",
        ":flag-dk::rocket: [Name], your age's reaching new heights. Maybe it’s time it came back to Earth? :stars:",
        ":flag-dk::gift_heart: [Name], you're a vintage model now. But remember, classic doesn't mean obsolete... yet! :ribbon:",
        ":flag-dk::spiral_calendar_pad: [Name], on your special day, remember: age is just a high score in the game of life. And you're winning! :flashlight:",
        ":flag-dk::trophy: [Name], here's to you, the MVP of making gray hair look... intentional! Go champ! :medal:",
        ":flag-dk::beers: [Name], remember, beers may get stale with age, but you? You're... fermenting? Cheers! :tumbler_glass:",
        ":flag-dk::sparkler: [Name], like a rare coin, you're gaining value. Too bad it's in laugh lines! Celebrate! :sun_with_face:",
        ":flag-dk::pizza: [Name], you're a slice above the rest. A slightly older, crustier slice, but still our favorite! :knife_fork_plate:",
        ":flag-dk::zany_face: [Name], you're not older – you're just a classic edition with added... character! Enjoy your day!"
    };

 */

/*
Alright, let's go for some playful mischief! Here's an "evil" twist to the greetings, all in good fun:

```java
    private static final String[] WISHES = {
        // ... (existing messages)

        ":flag-dk::alarm_clock: [Name], don't you love birthdays? Another year closer to looking like your passport photo. :birthday:",
        ":flag-dk::hatching_chick: Heard [Name]'s aging like a fine wine. That explains the sour notes! :bird:",
        ":flag-dk::video_game: Ding! [Name] leveled up in age! Still waiting for that charisma boost though... :trophy:",
        ":flag-dk::musical_note: [Name], if age was a song, you'd be a lengthy opera by now! Care for a solo? :headphones:",
        ":flag-dk::balloon: [Name], those aren't birthday balloons. They're your age’s SOS signals! :confetti_ball:",
        ":flag-dk::sunglasses: [Name], with age comes wisdom. Or in your case, more audacity to wear those clothes! :camera_with_flash:",
        ":flag-dk::memo: Remember when [Name] was young and spry? Me neither. Happy Birthday anyway! :fork_and_knife:",
        ":flag-dk::rainbow: [Name], you’re the pot of... aged gold at the end of the rainbow. Still shiny, right? :unicorn_face:",
        ":flag-dk::roller_coaster: [Name]'s age is like a roller coaster: surprising, a bit scary, and probably should come with height restrictions. :earth_americas:",
        ":flag-dk::clinking_glasses: Raising a glass to [Name], the poster child for 'Aged. Not Matured.' Cheers! :beer:",
        ":flag-dk::book: [Name]'s life is a book. A fantasy novel where age pretends to be just a number! :pen_fountain:",
        ":flag-dk::rocket: [Name], your age is shooting for the stars! Hope the memory's not too spaced out yet! :stars:",
        ":flag-dk::gift_heart: [Name], like old cheese, you’ve got that... distinct aroma of maturity! Relish it! :ribbon:",
        ":flag-dk::spiral_calendar_pad: Reminder: [Name]'s annual ‘Pretend I'm Not Aging’ fest! Join the delusion! :flashlight:",
        ":flag-dk::mage: [Name], may your day be as unforgettable as your... last attempt to karaoke. Pure magic! :crystal_ball:",
        ":flag-dk::trophy: [Name], older, bolder, and still not the holder of any real adult skills. Keep rocking! :medal:",
        ":flag-dk::beers: [Name], the secret to looking young is low lighting and good beer. Guzzle up! :tumbler_glass:",
        ":flag-dk::sparkler: [Name], they say age is illuminating. That’s just the candles setting your cake on fire! :sun_with_face:",
        ":flag-dk::pizza: [Name], like cold pizza on a hungover morning, you're... surprisingly still good! Cheers to another year! :knife_fork_plate:",
        ":flag-dk::zany_face: [Name], aging like a banana. Still sweet, just a bit more... spotted!"
    };
```

These are meant to be light-hearted and funny, but always ensure that the recipient has a good sense of humor and that these won't be perceived as too mean. Context and the relationship with the birthday person matter a lot!
 */