"""Read the real HTTP attachments from ClipRequestIntegrationTests with the OTIO SDK."""

import sys
from pathlib import Path

import opentimelineio as otio


def check(timeline):
    video, audio = timeline.tracks
    assert [video.kind, audio.kind] == ["Video", "Audio"]
    assert timeline.duration().to_seconds() == 2
    for track in (video, audio):
        assert len(track) == 2
        for index, clip in enumerate(track):
            assert clip.source_range.start_time.to_seconds() == 2 * index
            assert clip.source_range.duration.to_seconds() == 1
            assert clip.range_in_parent().start_time.to_seconds() == index
            assert clip.media_reference.available_range.duration.to_seconds() == 4
            assert clip.media_reference.target_url == "./intent-synthetic.mp4"
            if track is video:
                marker, = clip.markers
                assert marker.metadata["livecut"]["utterance_id"] == ("u1", "u3")[index]
                assert marker.marked_range.duration.to_seconds() == 1
                assert clip.transformed_time(marker.marked_range.start_time, track).to_seconds() == index
            else:
                assert not clip.markers


if __name__ == "__main__":
    directory = Path(sys.argv[1])
    for name in ("confirmed-http.otio", "confirmed-plan.otio"):
        document = otio.adapters.read_from_file(str(directory / name))
        check(document)
        check(otio.adapters.read_from_string(otio.adapters.write_to_string(document, "otio_json"), "otio_json"))
        print(f"{name} passed OTIO read, alignment, markers and round trip")
