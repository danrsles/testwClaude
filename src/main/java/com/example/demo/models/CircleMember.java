package com.example.demo.models;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * One row of circle_members, mapped only so the feed's JPQL can ask "is this
 * person in that circle?". Read-only: CircleRepository writes the table with
 * native SQL (addMemberIfAbsent, removeMember).
 */
@Entity
@Immutable
@Table(name = "circle_members")
public class CircleMember {

    @EmbeddedId
    private Key id;

    protected CircleMember() {
    }

    public Long getCircleId() {
        return id.circleId;
    }

    public Long getMemberId() {
        return id.memberId;
    }

    /** The table's primary key, (circle_id, member_id). */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "circle_id")
        private Long circleId;

        @Column(name = "member_id")
        private Long memberId;

        protected Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(circleId, key.circleId)
                    && Objects.equals(memberId, key.memberId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(circleId, memberId);
        }
    }
}
