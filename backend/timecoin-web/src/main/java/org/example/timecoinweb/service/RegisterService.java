package org.example.timecoinweb.service;


import org.example.pojo.User;

public interface RegisterService {
    public String register(User user);

    User login(User user);

    /**
     * 根据id查找自己的数据
     * @param id
     * @return
     */
    User selectSelf(Integer id);

    /** N2：修改密码（已由调用方做强度与旧密码校验）。 */
    void updatePassword(Integer id, String encodedPassword);
}
