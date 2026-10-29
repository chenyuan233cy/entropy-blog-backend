package com.qichi.blog.modules.music.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qichi.blog.cache.CacheService;
import com.qichi.blog.common.BizException;
import com.qichi.blog.modules.music.MusicDtos;
import com.qichi.blog.modules.music.entity.Music;
import com.qichi.blog.modules.music.entity.MusicPlaylist;
import com.qichi.blog.modules.music.mapper.MusicMapper;
import com.qichi.blog.modules.music.mapper.MusicPlaylistMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MusicService {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final MusicPlaylistMapper playlistMapper;
    private final MusicMapper musicMapper;
    private final CacheService cache;

    public MusicService(MusicPlaylistMapper playlistMapper, MusicMapper musicMapper, CacheService cache) {
        this.playlistMapper = playlistMapper;
        this.musicMapper = musicMapper;
        this.cache = cache;
    }

    public List<MusicDtos.PlaylistVO> playlists() {
        String key = "blog:music:playlists:" + cache.musicNs();
        MusicDtos.PlaylistVO[] cached = cache.get(key, MusicDtos.PlaylistVO[].class);
        if (cached != null) return List.of(cached);

        List<MusicPlaylist> list = playlistMapper.selectList(
                Wrappers.<MusicPlaylist>lambdaQuery().orderByAsc(MusicPlaylist::getSortOrder));
        List<Music> tracks = musicMapper.selectList(
                Wrappers.<Music>lambdaQuery().eq(Music::getStatus, 1).orderByAsc(Music::getSortOrder));

        Map<Long, List<MusicDtos.TrackVO>> byPlaylist = new LinkedHashMap<>();
        for (Music track : tracks) {
            if (track.getPlaylistId() == null) continue;
            byPlaylist.computeIfAbsent(track.getPlaylistId(), id -> new ArrayList<>()).add(toTrack(track));
        }

        List<MusicDtos.PlaylistVO> result = list.stream()
                .map(playlist -> new MusicDtos.PlaylistVO(
                        playlist.getId(), playlist.getName(), playlist.getSlug(), playlist.getDescription(),
                        playlist.getCoverUrl(), playlist.getSortOrder(),
                        byPlaylist.getOrDefault(playlist.getId(), List.of())))
                .toList();
        cache.put(key, result, TTL);
        return result;
    }

    // ------------------------------------------------------------------ admin

    public List<MusicDtos.TrackVO> allTracks() {
        return musicMapper.selectList(Wrappers.<Music>lambdaQuery()
                        .orderByAsc(Music::getPlaylistId).orderByAsc(Music::getSortOrder))
                .stream().map(this::toTrack).toList();
    }

    @Transactional
    public MusicDtos.PlaylistVO savePlaylist(Long id, MusicDtos.PlaylistSaveRequest request) {
        MusicPlaylist playlist = id == null ? new MusicPlaylist() : playlistMapper.selectById(id);
        if (playlist == null) throw BizException.notFound("歌单");
        playlist.setName(request.name().trim());
        playlist.setSlug(request.slug() == null || request.slug().isBlank()
                ? com.qichi.blog.common.SlugUtils.slugify(request.name(), "playlist")
                : com.qichi.blog.common.SlugUtils.slugify(request.slug(), "playlist"));
        playlist.setDescription(request.description());
        playlist.setCoverUrl(request.coverUrl());
        playlist.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        if (id == null) playlistMapper.insert(playlist);
        else playlistMapper.updateById(playlist);
        invalidate();
        return new MusicDtos.PlaylistVO(playlist.getId(), playlist.getName(), playlist.getSlug(),
                playlist.getDescription(), playlist.getCoverUrl(), playlist.getSortOrder(),
                tracksOf(playlist.getId()));
    }

    @Transactional
    public void deletePlaylist(Long id) {
        if (playlistMapper.selectById(id) == null) throw BizException.notFound("歌单");
        playlistMapper.deleteById(id);
        invalidate();
    }

    @Transactional
    public MusicDtos.TrackVO saveTrack(Long id, MusicDtos.TrackSaveRequest request) {
        Music track = id == null ? new Music() : musicMapper.selectById(id);
        if (track == null) throw BizException.notFound("曲目");
        track.setTitle(request.title().trim());
        track.setArtist(request.artist().trim());
        track.setAlbum(request.album());
        track.setCoverUrl(request.coverUrl());
        track.setAudioUrl(request.audioUrl().trim());
        track.setLyric(request.lyric());
        track.setDuration(request.duration() == null ? 0 : request.duration());
        track.setPlaylistId(request.playlistId());
        track.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        track.setStatus(request.status() == null ? 1 : request.status());
        if (id == null) musicMapper.insert(track);
        else musicMapper.updateById(track);
        invalidate();
        return toTrack(track);
    }

    @Transactional
    public void deleteTrack(Long id) {
        if (musicMapper.selectById(id) == null) throw BizException.notFound("曲目");
        musicMapper.deleteById(id);
        invalidate();
    }

    private List<MusicDtos.TrackVO> tracksOf(Long playlistId) {
        return musicMapper.selectList(Wrappers.<Music>lambdaQuery()
                        .eq(Music::getPlaylistId, playlistId).orderByAsc(Music::getSortOrder))
                .stream().map(this::toTrack).toList();
    }

    private MusicDtos.TrackVO toTrack(Music music) {
        return new MusicDtos.TrackVO(music.getId(), music.getTitle(), music.getArtist(), music.getAlbum(),
                music.getCoverUrl(), music.getAudioUrl(), music.getLyric(),
                music.getDuration() == null ? 0 : music.getDuration(), music.getSortOrder());
    }

    private void invalidate() {
        cache.bumpVersion("music");
    }
}
