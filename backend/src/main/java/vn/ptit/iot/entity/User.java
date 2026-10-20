package vn.ptit.iot.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="users")
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;

    @Column(nullable=false, unique=true, length=100) private String username;
    @Column(nullable=false, length=255) private String password;
    @Column(name="full_name", length=150) private String fullName;

    @Column(name="created_at", nullable=false, updatable=false, columnDefinition="DATETIME")
    private LocalDateTime createdAt;
    protected User() {}

    public Long getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getFullName() { return fullName; }

}
