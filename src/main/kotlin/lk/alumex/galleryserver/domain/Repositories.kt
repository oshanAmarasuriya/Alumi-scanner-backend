package lk.alumex.galleryserver.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface GalleryReleaseRepository : JpaRepository<GalleryRelease, Int> {

    /** The newest published release, or null when nothing has been published yet. */
    @Query("select r from GalleryRelease r where r.version = (select max(x.version) from GalleryRelease x)")
    fun findLatest(): GalleryRelease?
}

interface ClientDeviceRepository : JpaRepository<ClientDevice, String>

interface SyncEventRepository : JpaRepository<SyncEvent, Long>
