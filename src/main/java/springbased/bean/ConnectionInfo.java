package springbased.bean;

import org.bson.types.ObjectId;
import org.mongodb.morphia.annotations.Entity;
import org.mongodb.morphia.annotations.Id;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
public class ConnectionInfo {

	@Id
	@JsonIgnore
	private ObjectId id;

	private String username;

	private String password;

	private String url;

	private String login_role = "sysdba";

	public ConnectionInfo(String username, String password, String url) {
		super();
		this.username = username;
		this.password = password;
		this.url = url;
	}

	public ConnectionInfo(String username, String password, String url, String login_role) {
		super();
		this.username = username;
		this.password = password;
		this.url = url;
		this.login_role = login_role;
	}

	public ConnectionInfo() {
		super();
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getLogin_role() {
		return login_role;
	}

	public void setLogin_role(String login_role) {
		this.login_role = login_role;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((login_role == null) ? 0 : login_role.hashCode());
		result = prime * result + ((password == null) ? 0 : password.hashCode());
		result = prime * result + ((url == null) ? 0 : url.hashCode());
		result = prime * result + ((username == null) ? 0 : username.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ConnectionInfo other = (ConnectionInfo) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (login_role == null) {
			if (other.login_role != null)
				return false;
		} else if (!login_role.equals(other.login_role))
			return false;
		if (password == null) {
			if (other.password != null)
				return false;
		} else if (!password.equals(other.password))
			return false;
		if (url == null) {
			if (other.url != null)
				return false;
		} else if (!url.equals(other.url))
			return false;
		if (username == null) {
			if (other.username != null)
				return false;
		} else if (!username.equals(other.username))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "ConnectionInfo [id=" + id + ", username=" + username + ", password=" + password + ", url=" + url
				+ ", login_role=" + login_role + "]";
	}

}
