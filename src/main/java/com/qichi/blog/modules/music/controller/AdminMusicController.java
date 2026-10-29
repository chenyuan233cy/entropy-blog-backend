package com.qichi.blog.modules.music.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.music.MusicDtos;
import com.qichi.blog.modules.music.service.MusicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "后台 · 音乐")
public class AdminMusicController {

    private final MusicService musicService;

    public AdminMusicController(MusicService musicService) {
        this.musicService = musicService;
    }

    @GetMapping("/playlists")
    public R<List<MusicDtos.PlaylistVO>> playlists() {
        return R.ok(musicService.playlists());
    }

    @PostMapping("/playlists")
    @Operation(summary = "新建歌单")
    public R<MusicDtos.PlaylistVO> createPlaylist(@Valid @RequestBody MusicDtos.PlaylistSaveRequest request) {
        return R.ok(musicService.savePlaylist(null, request));
    }

    @PutMapping("/playlists/{id}")
    public R<MusicDtos.PlaylistVO> updatePlaylist(@PathVariable Long id, @Valid @RequestBody MusicDtos.PlaylistSaveRequest request) {
        return R.ok(musicService.savePlaylist(id, request));
    }

    @DeleteMapping("/playlists/{id}")
    public R<Void> deletePlaylist(@PathVariable Long id) {
        musicService.deletePlaylist(id);
        return R.ok();
    }

    @GetMapping("/music")
    public R<List<MusicDtos.TrackVO>> tracks() {
        return R.ok(musicService.allTracks());
    }

    @PostMapping("/music")
    @Operation(summary = "新增曲目")
    public R<MusicDtos.TrackVO> createTrack(@Valid @RequestBody MusicDtos.TrackSaveRequest request) {
        return R.ok(musicService.saveTrack(null, request));
    }

    @PutMapping("/music/{id}")
    public R<MusicDtos.TrackVO> updateTrack(@PathVariable Long id, @Valid @RequestBody MusicDtos.TrackSaveRequest request) {
        return R.ok(musicService.saveTrack(id, request));
    }

    @DeleteMapping("/music/{id}")
    public R<Void> deleteTrack(@PathVariable Long id) {
        musicService.deleteTrack(id);
        return R.ok();
    }
}
