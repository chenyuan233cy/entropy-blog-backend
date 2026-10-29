package com.qichi.blog.modules.music;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class MusicDtos {

    private MusicDtos() {}

    @Schema(description = "曲目")
    public record TrackVO(
            Long id, String title, String artist, String album,
            String coverUrl, String audioUrl, String lyric, int duration, Integer sortOrder) {}

    @Schema(description = "歌单")
    public record PlaylistVO(
            Long id, String name, String slug, String description, String coverUrl,
            Integer sortOrder, List<TrackVO> tracks) {}

    @Schema(description = "歌单新建 / 更新")
    public record PlaylistSaveRequest(
            @NotBlank(message = "歌单名不能为空") @Size(max = 80) String name,
            @Size(max = 80) String slug,
            @Size(max = 255) String description,
            @Size(max = 500) String coverUrl,
            Integer sortOrder) {}

    @Schema(description = "曲目新建 / 更新")
    public record TrackSaveRequest(
            @NotBlank(message = "曲名不能为空") @Size(max = 150) String title,
            @NotBlank(message = "艺术家不能为空") @Size(max = 150) String artist,
            @Size(max = 150) String album,
            @Size(max = 500) String coverUrl,
            @NotBlank(message = "音频地址不能为空") @Size(max = 500) String audioUrl,
            String lyric,
            Integer duration,
            Long playlistId,
            Integer sortOrder,
            Integer status) {}
}
