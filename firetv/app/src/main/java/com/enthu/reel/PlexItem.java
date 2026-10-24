package com.enthu.reel;

public class PlexItem {
    public String key;
    public String title;
    public String type;
    public String summary;
    public String thumb;
    public String art;
    public int year;

    public PlexItem() {
    }

    public PlexItem(String key, String title, String type, String summary, String thumb, String art, int year) {
        this.key = key;
        this.title = title;
        this.type = type;
        this.summary = summary;
        this.thumb = thumb;
        this.art = art;
        this.year = year;
    }
}
