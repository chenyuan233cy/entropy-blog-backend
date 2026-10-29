package com.qichi.blog.modules.music.controller;

import com.qichi.blog.common.R;
import com.qichi.blog.modules.music.MusicDtos;
import com.qichi.blog.modules.music.service.MusicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/music")
@Tag(name = "音乐")
public class MusicController {

    private final MusicService musicService;

    public MusicController(MusicService musicService) {
        this.musicService = musicService;
    }

    @GetMapping("/playlists")
    @Operation(summary = "歌单与曲目")
    public R<List<MusicDtos.PlaylistVO>> playlists() {
        return R.ok(musicService.playlists());
    }
}
