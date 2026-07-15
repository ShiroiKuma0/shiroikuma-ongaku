package app.simple.felicity.shared.constants

object AppConstants {
    /**
     * MusicBrainz requires every API client to identify itself with a User-Agent
     * that includes the application name, version, and a contact URL or email.
     * Requests without a proper User-Agent are rate-limited very aggressively.
     *
     * See: https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting
     */
    const val MUSIC_BRAINZ_USER_AGENT = "shiroikuma-ongaku/1.0 ( https://github.com/ShiroiKuma0/shiroikuma-ongaku )"
}