package net.ddns.candelapot.discordbot;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;

public class RecordingThread extends Thread
{
    TargetDataLine line;
    private byte[] data;

    public void init(){
        Mixer.Info[] info = AudioSystem.getMixerInfo();
        Mixer mixer = null;
        for (Mixer.Info inf : info)
        {
            if (inf.getName().startsWith("CABLE Output"))
            {
                mixer = AudioSystem.getMixer(inf);
                break;
            }
        }
        try
        {
            line = (TargetDataLine) mixer.getLine(mixer.getTargetLineInfo()[0]);
            line.open(AudioSendHandler.INPUT_FORMAT);
        }
        catch (LineUnavailableException e)
        {
            e.printStackTrace();
        }
        //data = new byte[line.getBufferSize() / 10];
        data = new byte[48000 * 16 / 8 * 2 / 1000 * 20];
        line.start();
    }
    public void run() {
        int numBytesRead;
        while(this.isAlive()){
            if(Main.EchoHandler.queue.size() < 10){
                numBytesRead =  line.read(data, 0, data.length);
                Main.EchoHandler.queue.add(new ByteData(data, numBytesRead));
            }
        }
    }
}
