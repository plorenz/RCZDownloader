package org.codemonk.rzc.teisho.downloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v23Tag;

public class RZCTeishoDownloader {

  private static final Pattern LINK_PATTERN = Pattern.compile(".*<a href=\"([^\"]+.mp3)\"[^>]*>([^<]*)</a>.*");
  private static final Pattern DATE_PATTERN_1 = Pattern.compile("(\\d\\d\\d\\d)-(\\d\\d?)[-_].*");

  static final String TARGET_DIR = "/home/plorenz/tmp/rcz/";

  private static class Podcast implements Comparable<Podcast> {
    public final static AtomicInteger indexCounter = new AtomicInteger();

    private int index = indexCounter.incrementAndGet();

    public String url;
    public String title;
    public String name;
    public int year;
    public int month;
    public int track;
    public int album;
    public File destinationFile;
    public String error;

    public Podcast(String url, String title) {
      this.url = url;
      this.title = title;
    }

    public void parse() {
      File file = new File(url);
      name = file.getName();
      Matcher matcher = DATE_PATTERN_1.matcher(name);
      if (matcher.matches()) {
        year = Integer.parseInt(matcher.group(1));
        month = Integer.parseInt(matcher.group(2));
      } else if (name.equals("1973 Oct 6.7.mp3")) {
        year = 1973;
        month = 10;
      } else if (name.startsWith("07_10")) {
        year = 2007;
        month = 10;
      } else {
        System.out.println("Unmatched: " + name);
      }

      if (year == 3009) {
        year = 2009;
      }

      album = year < 2007 ? 1970 : year;

      destinationFile = Paths.get(TARGET_DIR, String.valueOf(year), name).toFile();
      destinationFile.getParentFile().mkdirs();

      if (year < 1970 || year > 2015) {
        throw new IllegalArgumentException("Bad data for podcast: " + this);
      }
    }

    public void download() throws MalformedURLException, IOException {
      if (destinationFile.exists()) {
        System.out.println(name + " already downloaded. Skipping.");
      } else {
        boolean done = false;

        HttpURLConnection connection = null;

        int failureCount = 0;

        while (!done) {
          URL downloadUrl = new URL(url);

          connection = (HttpURLConnection)downloadUrl.openConnection();
          connection.setInstanceFollowRedirects(true);

          int status = 0;
          try {
            status = connection.getResponseCode();
          } catch (final Exception e) {
            failureCount++;
            error = "Failure while getting file: " + e.getMessage() + ". Source url: " + url;
            if (failureCount > 1) {
              System.err.println(error);
              return;
            } else {
              url = "https://s3-us-west-1.amazonaws.com/rzc/podcasts/" + name;
              System.err.println(error + ". Trying " + url + " instead.");
              continue;
            }
          }

          if (status != HttpURLConnection.HTTP_OK) {
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER) {
              url = connection.getHeaderField("Location");
              url = url.replace(" ", "%20");
              System.out.println("Redirecting to " + url);
            } else if (status == HttpURLConnection.HTTP_FORBIDDEN) {
              error = "Access denied to " + url;
              System.err.println(error);
              return;
            } else {
              throw new RuntimeException("Failure to download file " + url + ". Status: " + status);
            }
          } else {
            done = true;
          }
        }

        try (InputStream in = connection.getInputStream()) {
          System.out.println(index + ": Downloading " + name);
          Files.copy(in, destinationFile.toPath());
        }
      }
    }

    public void setTags() throws Exception {
      if (!destinationFile.exists()) {
        return;
      }
      System.out.println("Tagging: " + name + " with title: " + title);

      MP3File f      = (MP3File)AudioFileIO.read(destinationFile);

//      ID3v11Tag v1Tag = new ID3v11Tag();
//      v1Tag.setField(FieldKey.ARTIST, "Rochester Zen Center");
//      v1Tag.setField(FieldKey.TITLE, title);
//      v1Tag.setField(FieldKey.ALBUM, "Teishos (" + year + ")");
//      v1Tag.setField(FieldKey.YEAR, String.valueOf(year));
//      v1Tag.setField(FieldKey.TRACK, String.valueOf(track));
//      f.setID3v1Tag(v1Tag);

      AbstractID3v2Tag v2tag = new ID3v23Tag();

      v2tag.setField(FieldKey.ALBUM_ARTIST, "Rochester Zen Center");
      v2tag.setField(FieldKey.TITLE, title);

      String albumName = "Teishos (" + year + ")";
      if (this.album == 1970) {
        albumName = "Teishos (pre-millenial)";
      }

      v2tag.setField(FieldKey.ALBUM, albumName);
      v2tag.setField(FieldKey.YEAR, String.valueOf(year));
      v2tag.setField(FieldKey.TRACK, String.valueOf(track));
      f.setID3v2Tag(v2tag);

      f.save();

      System.out.println("Tagging: " + name + " complete.");
    }

    public void downloadAndTag() throws Exception {
      download();
      setTags();
    }

    @Override
    public String toString() {
      return "Podcast [idx=" + index + ", name=" + name + ", title=" + title + ", year=" + year + ", month=" + month
          + ", track=" + track + "]";
    }

    @Override
    public int compareTo(Podcast o) {
      if (year != o.year) {
        return year - o.year;
      }
      if (month != o.month) {
        return month - o.month;
      }
      return o.index - index;
    }
  }

  private List<Podcast> podcasts = new ArrayList<>();

  private void loadPodcastList() throws Exception {
    URL url = new URL("http://rzcpodcasts.blogspot.com/search?max-results=10000");
    try (InputStream in = url.openStream();
         InputStreamReader reader = new InputStreamReader(in);
         BufferedReader bufferReader = new BufferedReader(reader);) {
      bufferReader.lines().forEach( line -> checkLineForPodcast(line) );
    }
  }

  private void checkLineForPodcast(String line) {
    Matcher matcher = LINK_PATTERN.matcher(line);
    if (matcher.matches()) {
      Podcast podcast = new Podcast(matcher.group(1), matcher.group(2));
      podcasts.add(podcast);
    }
  }

  private void organizePodcasts() {
    podcasts.forEach( Podcast::parse );

    Collections.sort(podcasts);

    int album = 0;
    int track = 0;
    for (Podcast podcast : podcasts) {
      if (podcast.year != album) {
        album = podcast.year;
        track = 1;
      }
      podcast.track = track;
      track++;
    }
  }

  public void downloadPodcasts() throws Exception {
    loadPodcastList();
    System.out.println(Podcast.indexCounter.get() + " podcasts found. Processing.");
    organizePodcasts();

    int index = 1;
    for (Podcast podcast : podcasts) {
      System.out.println(index + ". Processing: " + podcast);
      podcast.downloadAndTag();
      index++;
    }

    System.out.println("Podcasts in error:");
    for (Podcast podcast : podcasts) {
      if (podcast.error != null) {
        System.out.println("Podcast: " + podcast);
        System.out.println("Error: " + podcast.error);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    new RZCTeishoDownloader().downloadPodcasts();
  }
}
