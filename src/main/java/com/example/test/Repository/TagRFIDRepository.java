package com.example.test.Repository;


import com.example.test.Model.tagread;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository

public interface TagRFIDRepository extends JpaRepository<tagread,Integer> {

}
