package io.github.wolf_359;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 *
 * @author cnaude
 */
public class RedditBroadcastEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final String message;

    /**
     *
     * @param message
     */
    public RedditBroadcastEvent(String message) {
        this.message = message;
    }

    /**
     *
     * @return
     */
    public String getMessage() {
        return this.message;
    }

    /**
     *
     * @return
     */
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    /**
     *
     * @return
     */
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
