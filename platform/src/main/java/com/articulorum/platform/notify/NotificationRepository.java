package com.articulorum.platform.notify;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stored notifications.
 */
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    /**
     * Lists the notifications of a container, newest first.
     *
     * @param path      the container path
     * @param direction received or sent
     * @return the notifications
     */
    List<NotificationEntity> findByPathAndDirectionOrderByReceivedDesc(String path,
        NotificationEntity.Direction direction);

    /**
     * Deletes the notifications of a resource and everything below it.
     *
     * @param path   the resource path
     * @param prefix {@code path + "/"}
     * @return the number of deleted notifications
     */
    @Modifying
    @Query("delete from NotificationEntity n where n.path = :path or n.path like concat(:prefix, '%')")
    int deleteSubtree(@Param("path") String path, @Param("prefix") String prefix);

}
