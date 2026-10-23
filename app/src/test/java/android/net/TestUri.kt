package android.net

class TestUri(private val s: String) : Uri() {
    override fun isHierarchical() = true
    override fun isRelative() = false
    override fun getScheme() = "content"
    override fun getSchemeSpecificPart() = "//media"
    override fun getEncodedSchemeSpecificPart() = "//media"
    override fun getAuthority() = "media"
    override fun getEncodedAuthority() = "media"
    override fun getUserInfo() = null
    override fun getEncodedUserInfo() = null
    override fun getHost() = "media"
    override fun getPort() = -1
    override fun getPath() = s
    override fun getEncodedPath() = s
    override fun getQuery() = null
    override fun getEncodedQuery() = null
    override fun getFragment() = null
    override fun getEncodedFragment() = null
    override fun getPathSegments() = listOf(s)
    override fun getLastPathSegment() = s
    override fun toString() = s
    override fun buildUpon() = null
    override fun compareTo(other: Uri?) = 0
    override fun describeContents() = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
}
