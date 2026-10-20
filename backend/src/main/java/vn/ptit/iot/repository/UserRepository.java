package vn.ptit.iot.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.ptit.iot.entity.User;
public interface UserRepository extends JpaRepository<User, Long> {
    java.util.Optional<User> findByUsername(String username);
}
