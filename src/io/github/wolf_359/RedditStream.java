package io.github.wolf_359;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class RedditStream extends JavaPlugin implements Listener {
	private ArrayList<String> alreadySeen;
	private String messageFormat;
	private BukkitRunnable storyChecker;
	private BukkitRunnable storySender;
	private List<JSONObject> waitingQueue;
	private URL redditURL;

	public static void main(String[] args) {
		new RedditStream().broadcastNewStories();
	}

	// @Override
	public void onEnable() {
		this.saveDefaultConfig();

		this.getServer().getPluginManager().registerEvents(this, this);

		Matcher m = Pattern.compile("&([0-9a-fk-or])").matcher(this.getConfig().getString("messageFormat"));
		StringBuffer s = new StringBuffer();
		while (m.find())
			m.appendReplacement(s, ChatColor.getByChar(m.group(1)).toString());
		m.appendTail(s);
		this.messageFormat = s.toString();

		storyChecker = new BukkitRunnable() {
			public void run() {
				retrieveNewStories();
			}
		};
		storySender = new BukkitRunnable() {
			public void run() {
				broadcastNewStories();
			}
		};
		if (this.getConfig().getBoolean("autoStart"))
			startScheduledJob();

	}

	// @Override
	public void onDisable() {
		this.getServer().getScheduler().cancelTasks(this);
	}

	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (cmd.getName().equalsIgnoreCase("redditstream")) {
			if (args.length == 0)
				return false;
			if (args[0].equalsIgnoreCase("start")) {
				this.getLogger().info("Starting Reddit polling service.");
				startScheduledJob();
			} else if (args[0].equalsIgnoreCase("stop")) {
				this.getLogger().info("Stopping Reddit polling service.");
				this.getServer().getScheduler().cancelTasks(this);
			} else
				return false;
			return true;
		}
		return false;
	}

	private void startScheduledJob() {
		alreadySeen = new ArrayList<String>();
		waitingQueue = Collections.synchronizedList(new LinkedList<JSONObject>());
		int pollingInterval = Math.max(120, this.getConfig().getInt("pollingInterval")) * 20;
		int broadcastInterval = Math.max(30, this.getConfig().getInt("broadcastInterval")) * 20;
		this.getServer().getScheduler().runTaskTimerAsynchronously(this, storyChecker, 0, pollingInterval);
		this.getServer().getScheduler().scheduleSyncRepeatingTask(this, storySender, broadcastInterval + 20, broadcastInterval);
		try {
			redditURL = new URL(buildRedditURL());
			JSONObject parentObject = getJSON(redditURL);
			if (parentObject == null)
				return;
			JSONArray jarray = parentObject.getJSONObject("data").getJSONArray("children");
			for (int i = 0; i < jarray.length(); i++) {
				JSONObject item = jarray.getJSONObject(i).getJSONObject("data");
				alreadySeen.add(item.getString("id"));
			}
		} catch (JSONException | IOException e) {
			this.getLogger().severe(e.getMessage());
		}
	}

	public void retrieveNewStories() {
		int queueLength = waitingQueue.size();
		try {
			JSONObject parentObject = getJSON(new URL(buildRedditURL()));
			if (parentObject == null)
				return;

			JSONArray jarray = parentObject.getJSONObject("data").getJSONArray("children");
			ArrayList<String> current_ids = new ArrayList<String>();
			for (int i = 0; i < jarray.length(); i++) {
				JSONObject item = jarray.getJSONObject(i).getJSONObject("data");
				current_ids.add(item.getString("id"));
				if (!alreadySeen.contains(item.getString("id"))) {
					waitingQueue.add(queueLength, item);
				}
			}
			alreadySeen = current_ids;
		} catch (IOException e) {
			this.getLogger().severe(e.getMessage());
			return;
		}
	}

	public void broadcastNewStories() {
		int broadcastCount = 0;
		while (!waitingQueue.isEmpty() && broadcastCount < this.getConfig().getInt("maxStories")) {
			JSONObject item = waitingQueue.remove(0);
			Matcher m = Pattern.compile("%\\{(author|author_flair_text|created|created_utc|downs|id|num_comments|over_18|score|selftext|reddit_url|subreddit|title|ups|url)(:[^\\}]*)?\\}",
					Pattern.CASE_INSENSITIVE).matcher(this.messageFormat);
			StringBuffer s = new StringBuffer();
			while (m.find())
				m.appendReplacement(s, getItemComponent(item, m.group(1).toLowerCase(), m.group(2)));
			m.appendTail(s);
			this.getServer().broadcastMessage(s.toString());
                        this.getServer().getPluginManager().callEvent(new RedditBroadcastEvent(s.toString()));			

			broadcastCount++;
		}

		if (this.getConfig().getBoolean("discardExcessStories"))
			waitingQueue.clear();
	}

	private String buildRedditURL() {
		String[] subs = this.getConfig().getString("subreddits", "minecraft").split(",");
		StringBuilder redditURL = new StringBuilder("http://www.reddit.com/r/");
		for (String string : subs) {
			redditURL.append(string);
			redditURL.append("+");
		}
		redditURL.append("/");
		redditURL.append(this.getConfig().getString("sortingMethod", "new"));
		redditURL.append("/.json");
		return redditURL.toString();
	}

	private String getItemComponent(JSONObject item, String component, String argument) {
		if (component.equals("reddit_url"))
			return "http://redd.it/" + item.getString("id");
		if (component.equals("author_flair_text"))
			return item.isNull("author_flair_text") || item.getString("author_flair_text").trim().isEmpty() ? StringEscapeUtils.unescapeHtml(item.getString("author")) : StringEscapeUtils
					.unescapeHtml(item.getString("author_flair_text"));
		if (component.equals("created") || component.equals("created_utc")) {
			SimpleDateFormat sdf = new SimpleDateFormat(argument.replaceFirst(":", ""));
			return sdf.format(new Date(item.getLong(component)));
		}
		if (component.equals("over_18")) {
			String[] split = argument.split(":");
			return item.getBoolean("over_18") ? split[1] : split[2];
		}
		if (component.equals("title") || component.equals("selftext")) {
			String text = StringEscapeUtils.unescapeHtml(item.getString(component));
			argument = argument.replaceFirst(":", "");
			if (argument.length() > 0)
				return text.substring(0, Math.min(Integer.parseInt(argument), text.length()));
			return text;
		}
		return item.isNull(component) ? "null" : StringEscapeUtils.unescapeHtml(item.getString(component));
	}

	public JSONObject getJSON(URL url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setDoOutput(true);
		connection.setUseCaches(false);
		connection.setReadTimeout(1000);
		connection.setRequestMethod("GET");
		connection.setRequestProperty("User-Agent", "Berger's Reddit API Bukkit Plugin");

		String response;
		try {
			response = new BufferedReader(new InputStreamReader(connection.getInputStream())).readLine();
			JSONObject object = new JSONObject(response);
			return object;
		} catch (FileNotFoundException e) {
			this.getLogger().severe("That subreddit does not exist. Check the spelling of the subreddit and sortingMethod config options.");
		} catch (SocketTimeoutException e) {
			this.getLogger().info("Reddit took to long to respond.");
		} catch (IOException e) {
			if (e.getMessage().contains("HTTP response code: 403"))
				this.getLogger().severe("That subreddit appears to be private.");
			else
				throw e;
		}
		return null;
	}
}
