package net.ddns.candelapot.discordbot;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Main extends ListenerAdapter
{
    private static String token;
    static Logger logger;

    public static void main(String[] args) throws LoginException
    {
        logger = LoggerFactory.getLogger(Main.class);
        logger.info("Hello Logback!!");
        // We only need 2 gateway intents enabled for this example:
        EnumSet<GatewayIntent> intents = EnumSet.of(
                // We need messages in guilds to accept commands from users
                GatewayIntent.GUILD_MESSAGES,
                // We need voice states to connect to the voice channel
                GatewayIntent.GUILD_VOICE_STATES
        );

        try{
            String path = new File(".").getAbsoluteFile().getParent();
            System.out.println(path);
            File file = new File(".\\token.txt");
            BufferedReader br = new BufferedReader(new FileReader(file));

            String str = br.readLine();
            while(str != null){
                if(str.startsWith("token:")){
                    token = str.substring(str.indexOf("token:") + "token:".length());
                }
                str = br.readLine();
            }
            br.close();
        }catch(FileNotFoundException e){
            System.out.println(e);
        }catch(IOException e){
            System.out.println(e);
        }

        // Start the JDA session with light mode (minimal cache)
        JDABuilder.createLight(token, intents)           // Use provided token from command line arguments
                .addEventListeners(new Main())  // Start listening with this listener
                .setActivity(Activity.playing("読み上げがんばる～　　　　")) // Inform users that we are jammin' it out
                .setStatus(OnlineStatus.ONLINE)     // Please don't disturb us while we're jammin'
                .enableCache(CacheFlag.VOICE_STATE)         // Enable the VOICE_STATE cache to find a user's connected voice channel
                .build();                                   // Login with these options

        RecordingThread thread = new RecordingThread();
        thread.init();
        thread.start();
    }


    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event)
    {
        Message message = event.getMessage();
        User author = message.getAuthor();
        String content = message.getContentRaw();
        Guild guild = event.getGuild();

        // Ignore message if bot
        if (author.isBot())
            return;

        if (content.startsWith("!echo "))
        {
            String arg = content.substring("!echo ".length());
            onEchoCommand(event, guild, arg);
        }
        else if (content.equals("!echo"))
        {
            onEchoCommand(event);
        }
        else{
            try {
                HttpRequest request = HttpRequest
                        .newBuilder(URI.create("http://localhost:50080/talk?text=" + content))
                        .build();

                HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
                HttpClient.newBuilder().build().send(request, bodyHandler);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Handle command without arguments.
     *
     * @param event The event for this command
     */
    private void onEchoCommand(GuildMessageReceivedEvent event)
    {
        // Note: None of these can be null due to our configuration with the JDABuilder!
        Member member = event.getMember();                              // Member is the context of the user for the specific guild, containing voice state and roles
        GuildVoiceState voiceState = member.getVoiceState();            // Check the current voice state of the user
        VoiceChannel channel = voiceState.getChannel();                 // Use the channel the user is currently connected to
        if (channel != null)
        {
            connectTo(channel);                                         // Join the channel of the user
            onConnecting(channel, event.getChannel());                  // Tell the user about our success
        }
        else
        {
            onUnknownChannel(event.getChannel(), "your voice channel"); // Tell the user about our failure
        }
    }

    /**
     * Handle command with arguments.
     *
     * @param event The event for this command
     * @param guild The guild where its happening
     * @param arg   The input argument
     */
    private void onEchoCommand(GuildMessageReceivedEvent event, Guild guild, String arg)
    {
        boolean isNumber = arg.matches("\\d+"); // This is a regular expression that ensures the input consists of digits
        VoiceChannel channel = null;
        if (isNumber)                           // The input is an id?
        {
            channel = guild.getVoiceChannelById(arg);
        }
        if (channel == null)                    // Then the input must be a name?
        {
            List<VoiceChannel> channels = guild.getVoiceChannelsByName(arg, true);
            if (!channels.isEmpty())            // Make sure we found at least one exact match
                channel = channels.get(0);      // We found a channel! This cannot be null.
        }

        TextChannel textChannel = event.getChannel();
        if (channel == null)                    // I have no idea what you want mr user
        {
            onUnknownChannel(textChannel, arg); // Let the user know about our failure
            return;
        }
        connectTo(channel);                     // We found a channel to connect to!
        onConnecting(channel, textChannel);     // Let the user know, we were successful!
    }

    /**
     * Inform user about successful connection.
     *
     * @param channel     The voice channel we connected to
     * @param textChannel The text channel to send the message in
     */
    private void onConnecting(VoiceChannel channel, TextChannel textChannel)
    {
        textChannel.sendMessage("Connecting to " + channel.getName()).queue(); // never forget to queue()!
    }

    /**
     * The channel to connect to is not known to us.
     *
     * @param channel The message channel (text channel abstraction) to send failure information to
     * @param comment The information of this channel
     */
    private void onUnknownChannel(MessageChannel channel, String comment)
    {
        channel.sendMessage("Unable to connect to ``" + comment + "``, no such channel!").queue(); // never forget to queue()!
    }

    /**
     * Connect to requested channel and start echo handler
     *
     * @param channel The channel to connect to
     */
    private void connectTo(VoiceChannel channel)
    {
        Guild guild = channel.getGuild();
        // Get an audio manager for this guild, this will be created upon first use for each guild
        AudioManager audioManager = guild.getAudioManager();
        // Create our Send/Receive handler for the audio connection
        EchoHandler handler = new EchoHandler();

        // The order of the following instructions does not matter!

        // Set the sending handler to our echo system
        audioManager.setSendingHandler(handler);
        // Connect to the voice channel
        audioManager.openAudioConnection(channel);
    }

    public static class EchoHandler implements AudioSendHandler
    {
        /*
            All methods in this class are called by JDA threads when resources are available/ready for processing.
            The receiver will be provided with the latest 20ms of PCM stereo audio
            Note you can receive even while setting yourself to deafened!
            The sender will provide 20ms of PCM stereo audio (pass-through) once requested by JDA
            When audio is provided JDA will automatically set the bot to speaking!
         */
        final static Queue<ByteData> queue = new ConcurrentLinkedQueue<>();
/*
        Disable per-user audio since we want to echo the entire channel and not specific users.
        @Override // give audio separately for each user that is speaking
        public boolean canReceiveUser()
        {
            // this is not useful if we want to echo the audio of the voice channel, thus disabled for this purpose
            return false;
        }
        @Override
        public void handleUserAudio(UserAudio userAudio) {} // per-user is not helpful in an echo system
*/

        /* Send Handling */

        @Override
        public boolean canProvide()
        {
            // If we have something in our buffer we can provide it to the send system
            return !queue.isEmpty();
        }

        @Override
        public ByteBuffer provide20MsAudio()
        {
            ByteData bd = queue.poll();
            return bd.data == null ? null : ByteBuffer.wrap(bd.data, 0, bd.length); // Wrap this in a java.nio.ByteBuffer
        }

        @Override
        public boolean isOpus()
        {
            // since we send audio that is received from discord we don't have opus but PCM
            return false;
        }
    }
}
