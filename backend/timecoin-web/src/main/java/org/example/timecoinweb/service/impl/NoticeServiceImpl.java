package org.example.timecoinweb.service.impl;

import org.example.pojo.Notice;
import org.example.timecoinweb.mapper.NoticeMapper;
import org.example.timecoinweb.service.NoticeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NoticeServiceImpl implements NoticeService {

    @Autowired
    private NoticeMapper noticeMapper;
    @Autowired
    private org.example.timecoinweb.service.NoticeCacheService cache;

    @Override
    public List<Notice> listRecent(int limit) {
        return cache.recent(limit);
    }

    @Override
    public List<Notice> listAll() {
        return noticeMapper.listAll();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void add(Notice notice) {
        notice.setCreateTime(LocalDateTime.now());
        noticeMapper.insert(notice);
        cache.advanceVersion();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void update(Notice notice) {
        noticeMapper.update(notice);
        cache.advanceVersion();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deleteById(int id) {
        noticeMapper.deleteById(id);
        cache.advanceVersion();
    }
}
