package com.articulorum.platform.service;

/**
 * Callback for data kept alongside LDP resources (access control lists, notifications) that must
 * disappear together with them.
 *
 * <p>{@link LdpService#delete} calls every listener bean inside its transaction, once per deleted
 * subtree, so a rollback also restores the attached data.</p>
 */
public interface ResourceDeletionListener {

    /**
     * Called when a resource and everything below it are deleted.
     *
     * @param path the path of the deleted resource
     */
    void subtreeDeleted(String path);

}
